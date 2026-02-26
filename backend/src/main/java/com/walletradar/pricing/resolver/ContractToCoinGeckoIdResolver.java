package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;

import java.util.Optional;

/**
 * Resolves token contract address + network to CoinGecko coin ID.
 * Used by CoinGeckoHistoricalResolver and SpotPriceResolver (ADR-022).
 */
public interface ContractToCoinGeckoIdResolver {

    /**
     * @param contractAddress token contract (EVM: 0x..., Solana: base58). Normalized lowercase.
     * @param networkId       blockchain network (may be null for config-only lookup)
     * @return CoinGecko coin id (e.g. "ethereum", "weth") or empty if unknown
     */
    Optional<String> resolve(String contractAddress, NetworkId networkId);
}
