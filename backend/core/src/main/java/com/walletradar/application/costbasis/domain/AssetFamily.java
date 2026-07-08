package com.walletradar.application.costbasis.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Code-level asset taxonomy for per-counterparty basis pools (ADR-015 §D4).
 */
public enum AssetFamily {
    STABLE_USD("USDT", "USDC", "USDE", "USDT0", "DAI", "TUSD", "BUSD");

    private final Set<String> memberSymbols;

    AssetFamily(String... memberSymbols) {
        this.memberSymbols = Set.of(memberSymbols);
    }

    public static String resolve(String assetSymbol) {
        if (assetSymbol == null || assetSymbol.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
        for (AssetFamily family : values()) {
            if (family.memberSymbols.contains(normalized)) {
                return family.name();
            }
        }
        return normalized;
    }
}
