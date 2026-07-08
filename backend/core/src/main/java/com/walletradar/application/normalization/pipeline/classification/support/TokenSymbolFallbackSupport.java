package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves display symbols and decimals for ERC-20 legs when explorer/RPC metadata is missing or blank.
 */
public final class TokenSymbolFallbackSupport {

    private static final Map<String, String> KNOWN_CONTRACT_SYMBOLS = Map.ofEntries(
            Map.entry(
                    "0x39de0f00189306062d79edec6dca5bb6bfd108f9",
                    "eUSDC-2"
            ),
            Map.entry("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC"),  // USDC on BASE
            Map.entry("0x4200000000000000000000000000000000000006", "WETH")    // WETH on BASE
    );

    private static final Map<String, Integer> KNOWN_CONTRACT_DECIMALS = Map.ofEntries(
            Map.entry("0x39de0f00189306062d79edec6dca5bb6bfd108f9", 6),   // eUSDC-2 on BASE
            Map.entry("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", 6),   // USDC on BASE
            Map.entry("0x4200000000000000000000000000000000000006", 18)    // WETH on BASE
    );

    private TokenSymbolFallbackSupport() {
    }

    public static String resolve(String assetContract, String assetSymbol) {
        if (assetSymbol != null && !assetSymbol.isBlank()) {
            return assetSymbol.trim();
        }
        if (assetContract == null || assetContract.isBlank()) {
            return assetSymbol;
        }
        String normalizedContract = assetContract.trim().toLowerCase(Locale.ROOT);
        String known = KNOWN_CONTRACT_SYMBOLS.get(normalizedContract);
        if (known != null) {
            return known;
        }
        String suffix = normalizedContract.length() >= 6
                ? normalizedContract.substring(normalizedContract.length() - 6)
                : normalizedContract;
        return "ERC20:" + suffix;
    }

    /**
     * Returns the known symbol for a contract address, or {@code null} if not in the static registry.
     */
    public static String resolveSymbolByContract(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return KNOWN_CONTRACT_SYMBOLS.get(contractAddress.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the known decimal precision for a contract address, or {@code null} if not in the static registry.
     */
    public static Integer resolveDecimalsByContract(String contractAddress) {
        if (contractAddress == null || contractAddress.isBlank()) {
            return null;
        }
        return KNOWN_CONTRACT_DECIMALS.get(contractAddress.trim().toLowerCase(Locale.ROOT));
    }
}
