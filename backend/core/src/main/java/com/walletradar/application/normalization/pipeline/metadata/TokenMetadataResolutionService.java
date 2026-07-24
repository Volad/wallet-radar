package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Unified token-metadata resolution (WS-7) enforcing a single, deterministic order everywhere
 * identity is resolved at NORMALIZATION:
 *
 * <pre>descriptor override → persistent cache → live resolver (write-through) → explicit unresolved</pre>
 *
 * <p>Descriptor overrides ({@link NetworkTokenOverrides}) are authoritative and are never overwritten
 * by a live value (a wrong live-defaulted decimals would silently corrupt an asset's recomputed
 * history). Live results are written through to the durable {@code token_metadata_cache} so a
 * subsequent 2-year renormalization is RPC-free and returns a stable symbol/decimals across runs —
 * satisfying the replay-must-not-call-RPC invariant.</p>
 *
 * <p>Invoked at the {@code CanonicalMetadataEnricher} seam (part of the Solana/TON normalization
 * pipeline) to resolve/finalise flow symbols and warm the cache. The static descriptor registries
 * ({@code SolanaSplTokenMetadataRegistry} / {@code TonJettonMetadataRegistry}) intentionally stay
 * descriptor-only and network-free so the builders remain pure and unit tests are deterministic;
 * this service owns the cache + live tiers.</p>
 */
@Service
@Slf4j
public class TokenMetadataResolutionService {

    private static final Pattern TON_RAW_ADDRESS = Pattern.compile("^-?\\d+:[0-9a-fA-F]{64}$");

    private final TokenMetadataCacheRepository cacheRepository;
    private final List<LiveTokenMetadataResolver> liveResolvers;

    public TokenMetadataResolutionService(TokenMetadataCacheRepository cacheRepository,
                                          List<LiveTokenMetadataResolver> liveResolvers) {
        this.cacheRepository = cacheRepository;
        this.liveResolvers = liveResolvers == null ? List.of() : List.copyOf(liveResolvers);
    }

    /**
     * Full resolution order: descriptor override → persistent cache → live resolver (write-through)
     * → explicit unresolved.
     */
    public ResolvedTokenMetadata resolve(NetworkId networkId, String contract) {
        if (networkId == null || contract == null || contract.isBlank()) {
            return ResolvedTokenMetadata.unresolved();
        }
        Optional<NetworkTokenOverrides.Override> descriptor = NetworkTokenOverrides.find(networkId, contract);
        String descriptorSymbol = descriptor.map(NetworkTokenOverrides.Override::symbol).orElse(null);
        Integer descriptorDecimals = descriptor.map(NetworkTokenOverrides.Override::effectiveDecimals).orElse(null);
        if (descriptorSymbol != null && descriptorDecimals != null) {
            return new ResolvedTokenMetadata(descriptorSymbol, descriptorDecimals,
                    ResolvedTokenMetadata.Source.DESCRIPTOR_OVERRIDE);
        }

        boolean descriptorPresent = descriptor.isPresent();
        String key = cacheKey(networkId, contract);
        String id = documentId(networkId, key);

        Optional<TokenMetadataCacheEntry> cached = findCached(id);
        String symbol = firstNonBlank(descriptorSymbol, cached.map(TokenMetadataCacheEntry::getSymbol).orElse(null));
        // Descriptor decimals are authoritative and must never be superseded by cache/live.
        Integer decimals = descriptorDecimals != null
                ? descriptorDecimals
                : cached.map(TokenMetadataCacheEntry::getDecimals).orElse(null);

        if (symbol != null && decimals != null) {
            ResolvedTokenMetadata.Source source = descriptorPresent
                    ? ResolvedTokenMetadata.Source.DESCRIPTOR_OVERRIDE
                    : ResolvedTokenMetadata.Source.PERSISTENT_CACHE;
            return new ResolvedTokenMetadata(symbol, decimals, source);
        }

        Optional<ResolvedTokenMetadata> live = resolveLive(networkId, contract);
        if (live.isPresent()) {
            ResolvedTokenMetadata liveValue = live.get();
            writeThrough(networkId, key, id, liveValue, cached.orElse(null));
            String mergedSymbol = firstNonBlank(symbol, liveValue.symbol());
            Integer mergedDecimals = decimals != null ? decimals : liveValue.decimals();
            if (mergedSymbol != null || mergedDecimals != null) {
                return new ResolvedTokenMetadata(mergedSymbol, mergedDecimals,
                        ResolvedTokenMetadata.Source.LIVE_RESOLVER);
            }
        }

        if (symbol != null || decimals != null) {
            ResolvedTokenMetadata.Source source = descriptorPresent
                    ? ResolvedTokenMetadata.Source.DESCRIPTOR_OVERRIDE
                    : ResolvedTokenMetadata.Source.PERSISTENT_CACHE;
            return new ResolvedTokenMetadata(symbol, decimals, source);
        }
        return ResolvedTokenMetadata.unresolved();
    }

    private Optional<TokenMetadataCacheEntry> findCached(String id) {
        try {
            return cacheRepository.findById(id);
        } catch (Exception ex) {
            // Cache is a best-effort accelerator; a read hiccup must not fail resolution.
            log.debug("token_metadata_cache read failed for {}: {}", id, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ResolvedTokenMetadata> resolveLive(NetworkId networkId, String contract) {
        for (LiveTokenMetadataResolver resolver : liveResolvers) {
            if (!resolver.supports(networkId)) {
                continue;
            }
            Optional<ResolvedTokenMetadata> resolved = resolver.resolve(networkId, contract);
            if (resolved != null && resolved.isPresent() && resolved.get().isResolved()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private void writeThrough(NetworkId networkId,
                              String key,
                              String id,
                              ResolvedTokenMetadata liveValue,
                              TokenMetadataCacheEntry existing) {
        try {
            Instant now = Instant.now();
            TokenMetadataCacheEntry entry = existing != null ? existing : new TokenMetadataCacheEntry();
            entry.setId(id);
            entry.setNetworkId(networkId.name());
            entry.setContract(key);
            if (liveValue.hasSymbol()) {
                entry.setSymbol(liveValue.symbol());
            }
            if (liveValue.hasDecimals()) {
                entry.setDecimals(liveValue.decimals());
            }
            entry.setSource(ResolvedTokenMetadata.Source.LIVE_RESOLVER.name());
            if (entry.getFirstSeenAt() == null) {
                entry.setFirstSeenAt(now);
            }
            entry.setUpdatedAt(now);
            cacheRepository.save(entry);
        } catch (Exception ex) {
            // Cache is a best-effort accelerator; a persistence hiccup must not fail normalization.
            log.debug("token_metadata_cache write-through failed for {} {}: {}", networkId, key, ex.getMessage());
        }
    }

    private static String documentId(NetworkId networkId, String key) {
        return networkId.name() + "|" + key;
    }

    /** Deterministic cache key per network address format so cross-form TON masters collapse. */
    private static String cacheKey(NetworkId networkId, String contract) {
        String trimmed = contract.trim();
        return switch (networkId) {
            case SOLANA -> trimmed;
            case TON -> tonRawKey(trimmed);
            default -> trimmed.toLowerCase(Locale.ROOT);
        };
    }

    private static String tonRawKey(String address) {
        for (String candidate : TonAddressCanonicalizer.lookupKeys(address)) {
            if (TON_RAW_ADDRESS.matcher(candidate).matches()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        return address.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
