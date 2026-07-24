package com.walletradar.application.normalization.pipeline.solana;

import com.walletradar.application.normalization.pipeline.metadata.NetworkTokenOverrides;
import com.walletradar.domain.common.NetworkId;

import java.util.Locale;
import java.util.Optional;

/**
 * Solana SPL mint → {symbol, decimals} descriptor-seed lookups (WS-7). Backed by the per-network
 * {@code token-overrides} in {@code network-descriptors.yml} (via {@link NetworkTokenOverrides}) —
 * the seeded SPL majors (USDC/USDT/wSOL) that Helius frequently omits. The static
 * {@code token-metadata.json} store it used to load was deleted; live resolution for unseeded mints
 * and the durable write-through cache are owned by
 * {@code com.walletradar.application.normalization.pipeline.metadata.TokenMetadataResolutionService}
 * (applied at the {@code CanonicalMetadataEnricher} seam), keeping this lookup deterministic and
 * network-free so the builder/classifier stay pure.
 *
 * <p>Solana mint addresses are base58 and case-sensitive, so keys are matched exactly. Returns
 * {@code null} when the mint is not seeded, letting the caller fall back to the raw mint / its own
 * default.</p>
 */
public final class SolanaSplTokenMetadataRegistry {

    private SolanaSplTokenMetadataRegistry() {
    }

    /** @return seeded upper-cased symbol for the mint, or {@code null} when not seeded. */
    public static String symbol(String mint) {
        return find(mint)
                .map(NetworkTokenOverrides.Override::symbol)
                .map(SolanaSplTokenMetadataRegistry::upper)
                .orElse(null);
    }

    /** @return seeded decimals for the mint, or {@code null} when not seeded. */
    public static Integer decimals(String mint) {
        return find(mint)
                .map(NetworkTokenOverrides.Override::effectiveDecimals)
                .orElse(null);
    }

    private static Optional<NetworkTokenOverrides.Override> find(String mint) {
        if (mint == null || mint.isBlank()) {
            return Optional.empty();
        }
        return NetworkTokenOverrides.find(NetworkId.SOLANA, mint);
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
