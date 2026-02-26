package com.walletradar.pricing.config;

import com.walletradar.domain.NetworkId;

import java.util.Optional;

/**
 * Maps WalletRadar NetworkId to CoinGecko asset platform ID.
 * Used for coins/list and onchain API lookups (ADR-022).
 */
public final class NetworkIdToCoinGeckoPlatformMapper {

    private NetworkIdToCoinGeckoPlatformMapper() {}

    private static final java.util.Map<NetworkId, String> PLATFORM_IDS = java.util.Map.of(
            NetworkId.ETHEREUM, "ethereum",
            NetworkId.ARBITRUM, "arbitrum-one",
            NetworkId.OPTIMISM, "optimism",
            NetworkId.POLYGON, "polygon-pos",
            NetworkId.BASE, "base",
            NetworkId.BSC, "binance-smart-chain",
            NetworkId.AVALANCHE, "avalanche",
            NetworkId.MANTLE, "mantle",
            NetworkId.SOLANA, "solana"
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
