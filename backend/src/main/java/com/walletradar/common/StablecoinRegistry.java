package com.walletradar.common;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry of stablecoin contract addresses (any supported network).
 * Used by StablecoinResolver to return $1.00 for USDC, USDT, DAI, GHO, USDe, FRAX per 01-domain.
 */
@Component
public class StablecoinRegistry {

    private static final Set<String> WELL_KNOWN_STABLECOINS = Stream.of(
            // USDC (multiple chains share same or we list mainnet; caller normalizes to lowercase)
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",  // Ethereum USDC
            "0xaf88d065e77c8cc2239327c5edb3a432268e5831",  // Arbitrum USDC
            "0x0b2c639c533813f4aa9d7837caf62653d097ff85",  // Optimism USDC
            "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359",  // Polygon USDC
            "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",  // Base USDC
            "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d",  // BSC USDC
            "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",  // Avalanche USDC
            // USDT
            "0xdac17f958d2ee523a2206206994597c13d831ec7",  // Ethereum USDT
            "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9",  // Arbitrum USDT
            "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58",  // Optimism USDT
            "0xc2132d05d31c914a87c6611c10748aeb04b58e8f",  // Polygon USDT
            "0x50b7545627a5162f82a992c33d87cd8af3e9e401",  // Avalanche USDT (e.g. Bridge)
            // DAI
            "0x6b175474e89094c44da98b954eedeac495271d0f",  // Ethereum DAI
            "0xda10009cbd5d07dd0cecc66161fc93d7c9000da1",  // Arbitrum DAI
            "0x8d11ec38a3eb5e956b052f67da8bdc9bef8abf3e",  // Optimism DAI (might be different)
            // GHO (Ethereum)
            "0x40d16fc9686d086299136be70377984c4e2e770a",
            // USDe (Ethena)
            "0x4c9edd5852cd905f086c759e8383e09bff1e68b3",
            // FRAX
            "0x853d955acef822db058eb8505911ed77f175b99e"   // Ethereum FRAX
    ).map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());

    /**
     * Returns true if the given contract address (any case) is a known stablecoin.
     */
    public boolean isStablecoin(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return false;
        }
        return WELL_KNOWN_STABLECOINS.contains(contractAddress.toLowerCase().strip());
    }
}
