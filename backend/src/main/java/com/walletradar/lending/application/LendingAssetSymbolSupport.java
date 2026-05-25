package com.walletradar.lending.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LendingAssetSymbolSupport {

    private static final List<String> RECEIPT_PREFIXES = List.of(
            "VARIABLEDEBT",
            "STABLEDEBT",
            "AMANA",
            "AMAN",
            "AARB",
            "AAVA",
            "ABAS",
            "AZKS",
            "AOPT",
            "APOL",
            "AETH",
            "AWETH",
            "AUSDC",
            "AUSDT",
            "ADAI",
            "EUSDC",
            "EUSDT",
            "EETH",
            "CWETH",
            "CETH",
            "CUSDC",
            "CUSDT",
            "FDAI",
            "FUSDC",
            "FUSDT",
            "SOUSDC",
            "SOUSDT",
            "SOWETH"
    );
    private static final List<String> MARKET_PREFIXES = List.of("MANTLE", "MAN", "AVALANCHE", "AVA", "ARBITRUM", "ARB", "BASE", "BAS", "ZKSYNC", "ZKS", "OPT", "POL");
    private static final Set<String> COMMON_UNDERLYINGS = Set.of(
            "ETH",
            "WETH",
            "WBTC",
            "BTC",
            "USDC",
            "USDT",
            "USDT0",
            "USD₮0",
            "DAI",
            "GHO",
            "USDE",
            "DEUSD",
            "EURC",
            "MNT",
            "ARB",
            "OP",
            "POL"
    );

    private LendingAssetSymbolSupport() {
    }

    /**
     * Underlying asset symbol for a lending receipt token (aToken-style), excluding debt markers.
     * Used by accounting continuity to map heterogeneous chain receipts into one {@code FAMILY:*} bucket.
     */
    public static String lendingReceiptLifecycleUnderlying(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String normalized = normalizeSymbol(symbol);
        if (isBorrowSymbol(normalized)) {
            return null;
        }
        if (!isLendingPositionSymbol(normalized)) {
            return null;
        }
        return lifecycleAsset(normalized);
    }

    static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    static String displaySymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    static String underlyingSymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return "UNKNOWN";
        }
        String cleaned = normalized.replace("-", "");
        if (cleaned.matches(".*USDC\\d+$")) {
            cleaned = cleaned.replaceAll("\\d+$", "");
        }
        if (cleaned.endsWith("USDCN")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        for (String prefix : RECEIPT_PREFIXES) {
            if (cleaned.startsWith(prefix) && cleaned.length() > prefix.length()) {
                String candidate = stripMarketPrefix(cleaned.substring(prefix.length()));
                if (isPlausibleUnderlying(candidate)) {
                    return candidate;
                }
            }
        }
        for (String prefix : List.of("SO", "A", "C", "E", "F")) {
            if (cleaned.startsWith(prefix) && cleaned.length() > prefix.length() + 1) {
                String candidate = cleaned.substring(prefix.length());
                if (COMMON_UNDERLYINGS.contains(candidate)) {
                    return candidate;
                }
            }
        }
        if (cleaned.startsWith("A") && cleaned.length() > 3 && isPlausibleUnderlying(cleaned.substring(1))) {
            return cleaned.substring(1);
        }
        return cleaned;
    }

    static boolean isBorrowSymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return normalized.contains("DEBT");
    }

    static boolean isLendingPositionSymbol(String symbol) {
        String normalized = normalizeSymbol(symbol).replace("-", "");
        if (normalized.isBlank()) {
            return false;
        }
        if (isBorrowSymbol(normalized)) {
            return true;
        }
        return RECEIPT_PREFIXES.stream().anyMatch(prefix ->
                normalized.equals(prefix) || normalized.startsWith(prefix) && normalized.length() > prefix.length()
        );
    }

    static boolean isStable(String symbol) {
        String normalized = underlyingSymbol(symbol);
        return normalized.equals("USDC")
                || normalized.equals("USDT")
                || normalized.equals("USDT0")
                || normalized.equals("USD₮0")
                || normalized.equals("DAI")
                || normalized.equals("GHO")
                || normalized.equals("USDE")
                || normalized.equals("DEUSD")
                || normalized.equals("EURC");
    }

    static String lifecycleAsset(String symbol) {
        String underlying = underlyingSymbol(symbol);
        return switch (underlying) {
            case "WETH" -> "ETH";
            case "USDT0", "USD₮0" -> "USDT";
            default -> underlying;
        };
    }

    private static boolean isPlausibleUnderlying(String value) {
        return value.length() >= 2 && value.length() <= 12 && value.chars().allMatch(Character::isLetterOrDigit);
    }

    private static String stripMarketPrefix(String value) {
        for (String prefix : MARKET_PREFIXES) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                String candidate = value.substring(prefix.length());
                if (COMMON_UNDERLYINGS.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return value;
    }
}
