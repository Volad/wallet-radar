package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;
import java.util.Map;

/**
 * Shared accounting-family identity contract for continuity-preserving replay.
 */
public final class AccountingAssetFamilySupport {

    private static final String FAMILY_BTC = "FAMILY:BTC";
    private static final String FAMILY_ETH = "FAMILY:ETH";
    private static final String FAMILY_AVAX = "FAMILY:AVAX";
    private static final String FAMILY_MNT = "FAMILY:MNT";
    private static final String FAMILY_USDC = "FAMILY:USDC";
    private static final String FAMILY_USDT = "FAMILY:USDT";
    private static final String FAMILY_DEUSD = "FAMILY:DEUSD";
    private static final String FAMILY_WSTUSR = "FAMILY:WSTUSR";

    private static final Map<String, String> SYMBOL_FAMILIES = Map.ofEntries(
            Map.entry("BTC", FAMILY_BTC),
            Map.entry("WBTC", FAMILY_BTC),
            Map.entry("AARBWBTC", FAMILY_BTC),
            Map.entry("AETHWBTC", FAMILY_BTC),
            Map.entry("ALINWBTC", FAMILY_BTC),
            Map.entry("AMANWBTC", FAMILY_BTC),
            Map.entry("AZKSWBTC", FAMILY_BTC),
            Map.entry("ETH", FAMILY_ETH),
            Map.entry("WETH", FAMILY_ETH),
            Map.entry("AETHWETH", FAMILY_ETH),
            Map.entry("AARBWETH", FAMILY_ETH),
            Map.entry("ALINWETH", FAMILY_ETH),
            Map.entry("AMANWETH", FAMILY_ETH),
            Map.entry("AZKSWETH", FAMILY_ETH),
            Map.entry("VBETH", FAMILY_ETH),
            Map.entry("YVVBETH", FAMILY_ETH),
            Map.entry("STETH", FAMILY_ETH),
            Map.entry("WSTETH", FAMILY_ETH),
            Map.entry("RETH", FAMILY_ETH),
            Map.entry("CBETH", FAMILY_ETH),
            Map.entry("EETH", FAMILY_ETH),
            Map.entry("WEETH", FAMILY_ETH),
            Map.entry("EZETH", FAMILY_ETH),
            Map.entry("RSETH", FAMILY_ETH),
            Map.entry("OSETH", FAMILY_ETH),
            Map.entry("METH", FAMILY_ETH),
            Map.entry("CMETH", FAMILY_ETH),
            Map.entry("AVAX", FAMILY_AVAX),
            Map.entry("WAVAX", FAMILY_AVAX),
            Map.entry("SAVAX", FAMILY_AVAX),
            Map.entry("AAVAWAVAX", FAMILY_AVAX),
            Map.entry("AAVASAVAX", FAMILY_AVAX),
            Map.entry("MNT", FAMILY_MNT),
            Map.entry("WMNT", FAMILY_MNT),
            Map.entry("AMANMNT", FAMILY_MNT),
            Map.entry("USDC", FAMILY_USDC),
            Map.entry("VBUSDC", FAMILY_USDC),
            Map.entry("USDBC", FAMILY_USDC),
            Map.entry("USDT", FAMILY_USDT),
            Map.entry("USDT0", FAMILY_USDT),
            Map.entry("USD₮0", FAMILY_USDT),
            Map.entry("DEUSD", FAMILY_DEUSD),
            Map.entry("WSTUSR", FAMILY_WSTUSR)
    );

    private AccountingAssetFamilySupport() {
    }

    public static String continuityIdentity(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        return continuityIdentity(flow.getAssetSymbol(), flow.getAssetContract());
    }

    public static String continuityIdentity(String assetSymbol, String assetContract) {
        String symbol = normalizeSymbol(assetSymbol);
        String familyIdentity = SYMBOL_FAMILIES.get(symbol);
        if (familyIdentity != null) {
            return familyIdentity;
        }
        familyIdentity = inferredFamilyIdentity(symbol);
        if (familyIdentity != null) {
            return familyIdentity;
        }
        String contract = normalizeContract(assetContract);
        if (contract != null) {
            return contract;
        }
        return symbol.isBlank() ? null : "SYMBOL:" + symbol;
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        return contract.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String inferredFamilyIdentity(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        if ("EEUSDC".equals(symbol) || matchesEulerIndexedReceipt(symbol, "EUSDC-")) {
            return FAMILY_USDC;
        }
        if (matchesEulerIndexedReceipt(symbol, "EUSDT-")
                || matchesEulerIndexedReceipt(symbol, "EUSDT0-")
                || matchesEulerIndexedReceipt(symbol, "EUSD₮0-")) {
            return FAMILY_USDT;
        }
        if (matchesEulerIndexedReceipt(symbol, "EWEETH-")) {
            return FAMILY_ETH;
        }
        if (matchesEulerIndexedReceipt(symbol, "EWSTUSR-")) {
            return FAMILY_WSTUSR;
        }
        if (matchesEulerIndexedReceipt(symbol, "EDEUSD-")) {
            return FAMILY_DEUSD;
        }
        return null;
    }

    private static boolean matchesEulerIndexedReceipt(String symbol, String prefix) {
        if (symbol == null || prefix == null || !symbol.startsWith(prefix)) {
            return false;
        }
        String suffix = symbol.substring(prefix.length());
        if (suffix.isBlank()) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
