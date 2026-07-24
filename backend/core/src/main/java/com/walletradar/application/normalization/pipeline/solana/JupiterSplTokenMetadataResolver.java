package com.walletradar.application.normalization.pipeline.solana;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.jupiter.JupiterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Resolves Solana SPL mint → {symbol, decimals} for display/accounting, consulting sources in a
 * fixed order (ADR-068):
 *
 * <ol>
 *   <li>{@link SolanaSplTokenMetadataRegistry} — config-seeded majors/stablecoins (USDC/USDT/wSOL),
 *       matched exactly (base58 case-sensitive), never a network call.</li>
 *   <li>Jupiter Tokens API via {@link JupiterClient}, cached in a Caffeine cache (negative results
 *       cached too, so an unresolvable mint is not re-queried within the TTL).</li>
 *   <li>Unresolved → empty, letting the caller fall back to the raw mint as the symbol.</li>
 * </ol>
 *
 * <p>Never throws: any Jupiter error resolves to an empty entry and is logged at debug.</p>
 */
@Component
@Slf4j
public class JupiterSplTokenMetadataResolver {

    private final JupiterClient jupiterClient;
    private final Cache<String, ResolvedSplToken> cache;

    public JupiterSplTokenMetadataResolver(JupiterClient jupiterClient, JupiterProperties jupiterProperties) {
        this.jupiterClient = jupiterClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1L, jupiterProperties.getCacheMaxSize()))
                .expireAfterWrite(Math.max(1L, jupiterProperties.getCacheTtlHours()), TimeUnit.HOURS)
                .build();
    }

    /** @return resolved upper-cased symbol for the mint, or empty when unresolvable. */
    public Optional<String> resolveSymbol(String mint) {
        ResolvedSplToken resolved = resolve(mint);
        return resolved == null ? Optional.empty() : Optional.ofNullable(resolved.symbol());
    }

    /** @return resolved token decimals for the mint, or empty when unresolvable. */
    public Optional<Integer> resolveDecimals(String mint) {
        ResolvedSplToken resolved = resolve(mint);
        return resolved == null ? Optional.empty() : Optional.ofNullable(resolved.decimals());
    }

    private ResolvedSplToken resolve(String mint) {
        if (mint == null || mint.isBlank()) {
            return null;
        }
        String key = mint.trim();
        String seededSymbol = SolanaSplTokenMetadataRegistry.symbol(key);
        if (seededSymbol != null && !seededSymbol.isBlank()) {
            return new ResolvedSplToken(seededSymbol, SolanaSplTokenMetadataRegistry.decimals(key));
        }
        return cache.get(key, this::loadFromJupiter);
    }

    private ResolvedSplToken loadFromJupiter(String mint) {
        try {
            return jupiterClient.fetchTokenMetadata(mint)
                    .map(metadata -> new ResolvedSplToken(upper(metadata.symbol()), metadata.decimals()))
                    .orElse(ResolvedSplToken.EMPTY);
        } catch (Exception ex) {
            log.debug("Jupiter SPL metadata resolution failed for mint {}: {}", mint, ex.getMessage());
            return ResolvedSplToken.EMPTY;
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** Resolved metadata; either or both fields may be {@code null} when unresolvable. */
    public record ResolvedSplToken(String symbol, Integer decimals) {
        static final ResolvedSplToken EMPTY = new ResolvedSplToken(null, null);
    }
}
