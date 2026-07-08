package com.walletradar.application.lending.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class LendingAssetSymbolSupport {

    private static final Pattern EULER_INDEXED_RECEIPT = Pattern.compile("^E([A-Z0-9]+)-(\\d+)$");
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
            "FWETH",
            "FETH",
            "SOUSDC",
            "SOUSDT",
            "SOWETH"
    );
    private static final List<String> MARKET_PREFIXES = List.of("MANTLE", "MAN", "AVALANCHE", "AVA", "ARBITRUM", "ARB", "BASE", "BAS", "ZKSYNC", "ZKS", "OPT", "POL");
    private static final Pattern MORPHO_GT_VAULT_SHARE = Pattern.compile("^GT([A-Z0-9]{2,12})C$");
    private static final Pattern MORPHO_MC_VAULT_SHARE = Pattern.compile("^(MC|RE7)([A-Z0-9]{2,12})$");
    private static final Set<String> EULER_COLLISION_GUARD = Set.of(
            "EURC",
            "EUSDE",
            "ETHFI",
            "EIGEN",
            "ENA"
    );
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
            "WMNT",
            "ARB",
            "OP",
            "POL",
            "WSTETH",
            "WEETH",
            "SAVAX",
            "WAVAX",
            "USD0"
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
        String eulerIndexed = eulerIndexedUnderlying(normalized);
        if (eulerIndexed != null) {
            return eulerIndexed;
        }
        String morphoUnderlying = morphoVaultUnderlying(normalized);
        if (morphoUnderlying != null) {
            return morphoUnderlying;
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
                if (COMMON_UNDERLYINGS.contains(candidate) && !isEulerCollision(candidate, prefix)) {
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
        String normalized = normalizeSymbol(symbol);
        if (normalized.isBlank()) {
            return false;
        }
        if (isBorrowSymbol(normalized)) {
            return true;
        }
        if (matchesEulerIndexedReceipt(normalized)) {
            return true;
        }
        if (matchesMorphoVaultShare(normalized)) {
            return true;
        }
        String cleaned = normalized.replace("-", "");
        return RECEIPT_PREFIXES.stream().anyMatch(prefix ->
                cleaned.equals(prefix) || cleaned.startsWith(prefix) && cleaned.length() > prefix.length()
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
            case "WETH", "WEETH" -> "ETH";
            case "USDT0", "USD₮0" -> "USDT";
            case "WBTC" -> "BTC";
            case "WMNT" -> "MNT";
            case "WAVAX", "SAVAX" -> "AVAX";
            default -> underlying;
        };
    }

    private static boolean matchesEulerIndexedReceipt(String normalized) {
        var matcher = EULER_INDEXED_RECEIPT.matcher(normalized);
        if (!matcher.matches()) {
            return false;
        }
        String underlying = matcher.group(1);
        return isPlausibleUnderlying(underlying) && !EULER_COLLISION_GUARD.contains(underlying);
    }

    private static String eulerIndexedUnderlying(String normalized) {
        var matcher = EULER_INDEXED_RECEIPT.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        String underlying = matcher.group(1);
        if (!isPlausibleUnderlying(underlying) || EULER_COLLISION_GUARD.contains(underlying)) {
            return null;
        }
        return underlying;
    }

    private static boolean matchesMorphoVaultShare(String normalized) {
        return morphoVaultUnderlying(normalized) != null;
    }

    private static String morphoVaultUnderlying(String normalized) {
        String cleaned = normalized.replace("-", "");
        var gtMatcher = MORPHO_GT_VAULT_SHARE.matcher(cleaned);
        if (gtMatcher.matches()) {
            return plausibleMorphoUnderlying(gtMatcher.group(1));
        }
        var mcMatcher = MORPHO_MC_VAULT_SHARE.matcher(cleaned);
        if (mcMatcher.matches()) {
            return plausibleMorphoUnderlying(mcMatcher.group(2));
        }
        return null;
    }

    private static String plausibleMorphoUnderlying(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (COMMON_UNDERLYINGS.contains(candidate) || isPlausibleUnderlying(candidate)) {
            return candidate;
        }
        return null;
    }

    private static boolean isEulerCollision(String candidate, String prefix) {
        return "E".equals(prefix) && EULER_COLLISION_GUARD.contains(candidate);
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
