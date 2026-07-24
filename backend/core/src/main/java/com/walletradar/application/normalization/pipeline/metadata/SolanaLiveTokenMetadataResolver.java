package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.solana.jupiter.JupiterClient;
import com.walletradar.platform.networks.solana.metaplex.MetaplexMetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Solana {@link LiveTokenMetadataResolver} backed directly by the free-tier {@link JupiterClient}
 * Tokens API with an on-chain Metaplex fallback (WS-7). It calls the transports directly — never the
 * descriptor-backed {@code SolanaSplTokenMetadataRegistry} nor {@link TokenMetadataResolutionService}
 * — so there is no recursion through the resolution layer.
 *
 * <p>Two-tier symbol resolution: Jupiter is primary (it also carries decimals). When Jupiter returns
 * no symbol (long-tail / unverified mints commonly resolve decimals-only, or the mint is absent from
 * Jupiter entirely), the {@link MetaplexMetadataClient} reads the on-chain Metaplex metadata PDA for
 * the symbol. Decimals from Jupiter are always preserved (financially load-bearing); the Metaplex
 * tier contributes only the symbol. Never throws — a venue error resolves to empty.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SolanaLiveTokenMetadataResolver implements LiveTokenMetadataResolver {

    private final JupiterClient jupiterClient;
    private final MetaplexMetadataClient metaplexMetadataClient;

    @Override
    public boolean supports(NetworkId networkId) {
        return networkId == NetworkId.SOLANA;
    }

    @Override
    public Optional<ResolvedTokenMetadata> resolve(NetworkId networkId, String contract) {
        if (!supports(networkId) || contract == null || contract.isBlank()) {
            return Optional.empty();
        }
        String mint = contract.trim();
        String symbol = null;
        Integer decimals = null;
        try {
            Optional<JupiterClient.JupiterTokenMetadata> jupiter = jupiterClient.fetchTokenMetadata(mint);
            if (jupiter.isPresent()) {
                symbol = upper(jupiter.get().symbol());
                decimals = jupiter.get().decimals();
            }
        } catch (Exception ex) {
            log.debug("Solana Jupiter metadata resolution failed for mint {}: {}", mint, ex.getMessage());
        }

        if (symbol == null) {
            symbol = resolveMetaplexSymbol(mint);
        }

        if (symbol == null && decimals == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTokenMetadata(symbol, decimals, ResolvedTokenMetadata.Source.LIVE_RESOLVER));
    }

    /** On-chain Metaplex fallback used only for the symbol; never throws. */
    private String resolveMetaplexSymbol(String mint) {
        try {
            return metaplexMetadataClient.fetchMetadata(mint)
                    .map(MetaplexMetadataClient.MetaplexTokenMetadata::symbol)
                    .map(SolanaLiveTokenMetadataResolver::upper)
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("Solana Metaplex metadata resolution failed for mint {}: {}", mint, ex.getMessage());
            return null;
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
