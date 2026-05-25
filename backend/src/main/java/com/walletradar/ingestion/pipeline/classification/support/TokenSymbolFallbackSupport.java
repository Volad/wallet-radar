package com.walletradar.ingestion.pipeline.classification.support;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves display symbols for ERC-20 legs when explorer/RPC metadata is missing or blank.
 */
public final class TokenSymbolFallbackSupport {

    private static final Map<String, String> KNOWN_CONTRACT_SYMBOLS = Map.ofEntries(
            Map.entry(
                    "0x39de0f00189306062d79edec6dca5bb6bfd108f9",
                    "eUSDC-2"
            )
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
}
