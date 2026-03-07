package com.walletradar.pricing.config;

import com.walletradar.domain.common.NetworkId;

import java.util.Optional;

/**
 * Maps WalletRadar NetworkId to CoinGecko asset platform ID.
 * Used for coins/list and onchain API lookups (ADR-022).
 */
public final class NetworkIdToCoinGeckoPlatformMapper {

    private NetworkIdToCoinGeckoPlatformMapper() {}

    private static final java.util.Map<NetworkId, String> PLATFORM_IDS = java.util.Map.ofEntries(
            java.util.Map.entry(NetworkId.ETHEREUM, "ethereum"),
            java.util.Map.entry(NetworkId.ARBITRUM, "arbitrum-one"),
            java.util.Map.entry(NetworkId.OPTIMISM, "optimism"),
            java.util.Map.entry(NetworkId.POLYGON, "polygon-pos"),
            java.util.Map.entry(NetworkId.BASE, "base"),
            java.util.Map.entry(NetworkId.BSC, "binance-smart-chain"),
            java.util.Map.entry(NetworkId.AVALANCHE, "avalanche"),
            java.util.Map.entry(NetworkId.MANTLE, "mantle"),
            java.util.Map.entry(NetworkId.LINEA, "linea"),
            java.util.Map.entry(NetworkId.UNICHAIN, "unichain"),
            java.util.Map.entry(NetworkId.ZKSYNC, "zksync"),
            java.util.Map.entry(NetworkId.SOLANA, "solana")
    );

    /**
     * @param networkId blockchain network
     * @return CoinGecko platform ID (e.g. "ethereum", "arbitrum-one") or empty if unknown
     */
    public static Optional<String> toPlatformId(NetworkId networkId) {
        if (networkId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLATFORM_IDS.get(networkId));
    }
}
