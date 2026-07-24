package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.application.normalization.pipeline.metadata.NetworkTokenOverrides;

/**
 * EVM contract → token metadata (symbol / decimals / decimal-override) lookups (WS-7). Backed by the
 * per-network {@code token-overrides} in {@code network-descriptors.yml} (single source of truth,
 * queried via {@link NetworkTokenOverrides}); the static {@code token-metadata.json} store it used to
 * load was deleted.
 *
 * <p>The legacy two-group split ({@code fallbackTokens} / {@code builderTokens}) collapsed into one
 * per-contract override entry. Field semantics are preserved so behaviour is unchanged:</p>
 * <ul>
 *   <li>{@link #fallbackSymbol}/{@link #fallbackDecimals} and {@link #builderSymbol}/{@link #builderDecimals}
 *       read the entry's fallback {@code symbol}/{@code decimals} (applied only when the source omits them);</li>
 *   <li>{@link #decimalOverride} reads the authoritative {@code decimal-override} that ALWAYS replaces a
 *       known-wrong explorer decimal (e.g. soUSDC).</li>
 * </ul>
 *
 * <p>EVM contract addresses are globally namespaced by the {@code 0x…} format, so lookups use the
 * EVM union across networks and are lowercased.</p>
 */
public final class TokenMetadataRegistry {

    private TokenMetadataRegistry() {
    }

    public static String fallbackSymbol(String contract) {
        return NetworkTokenOverrides.findEvm(contract)
                .map(NetworkTokenOverrides.Override::symbol)
                .orElse(null);
    }

    public static Integer fallbackDecimals(String contract) {
        return NetworkTokenOverrides.findEvm(contract)
                .map(NetworkTokenOverrides.Override::decimals)
                .orElse(null);
    }

    public static Integer decimalOverride(String contract) {
        return NetworkTokenOverrides.findEvm(contract)
                .map(NetworkTokenOverrides.Override::decimalOverride)
                .orElse(null);
    }

    public static String builderSymbol(String contract) {
        return NetworkTokenOverrides.findEvm(contract)
                .map(NetworkTokenOverrides.Override::symbol)
                .orElse(null);
    }

    public static Integer builderDecimals(String contract) {
        return NetworkTokenOverrides.findEvm(contract)
                .map(NetworkTokenOverrides.Override::decimals)
                .orElse(null);
    }
}
