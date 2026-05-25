package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.lending.application.LendingAssetSymbolSupport;

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
    private static final String FAMILY_USDE = "FAMILY:USDE";
    private static final String FAMILY_WSTUSR = "FAMILY:WSTUSR";
    private static final String FAMILY_ARB = "FAMILY:ARB";
    private static final String FAMILY_SOL = "FAMILY:SOL";

    private static final Map<String, String> SYMBOL_FAMILIES = Map.ofEntries(
            // BTC family
            Map.entry("BTC", FAMILY_BTC),
            Map.entry("WBTC", FAMILY_BTC),
            Map.entry("AARBWBTC", FAMILY_BTC),
            Map.entry("AETHWBTC", FAMILY_BTC),
            Map.entry("ALINWBTC", FAMILY_BTC),
            Map.entry("AMANWBTC", FAMILY_BTC),
            Map.entry("AZKSWBTC", FAMILY_BTC),
            Map.entry("ABASWBTC", FAMILY_BTC),
            Map.entry("AOPTWBTC", FAMILY_BTC),
            // ETH family
            Map.entry("ETH", FAMILY_ETH),
            Map.entry("WETH", FAMILY_ETH),
            Map.entry("AWETH", FAMILY_ETH),
            Map.entry("AETHWETH", FAMILY_ETH),
            Map.entry("AARBWETH", FAMILY_ETH),
            Map.entry("ALINWETH", FAMILY_ETH),
            Map.entry("AMANWETH", FAMILY_ETH),
            Map.entry("AZKSWETH", FAMILY_ETH),
            Map.entry("ABASWETH", FAMILY_ETH),
            Map.entry("AOPTWETH", FAMILY_ETH),
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
            // SOL family
            Map.entry("SOL", FAMILY_SOL),
            Map.entry("BBSOL", FAMILY_SOL),
            // ARB family
            Map.entry("ARB", FAMILY_ARB),
            Map.entry("AARBARB", FAMILY_ARB),
            // USDC family
            Map.entry("USDC", FAMILY_USDC),
            Map.entry("USDBC", FAMILY_USDC),
            Map.entry("AAVAUSDC", FAMILY_USDC),
            Map.entry("AMANUSDC", FAMILY_USDC),
            Map.entry("AARBUSDC", FAMILY_USDC),
            Map.entry("AETHUSDC", FAMILY_USDC),
            Map.entry("ABASUSDC", FAMILY_USDC),
            Map.entry("AOPTUSDC", FAMILY_USDC),
            Map.entry("AZKSUSDC", FAMILY_USDC),
            Map.entry("VBUSDC", FAMILY_USDC),
            Map.entry("EUSDC", FAMILY_USDC),
            Map.entry("EEUSDC", FAMILY_USDC),
            // Cycle/6 C1: Morpho-vault / Fluid-vault / Gauntlet / Re7 / Seamless USDC receipt tokens.
            // Without these mappings, LENDING_DEPOSIT/LENDING_WITHDRAW + VAULT_DEPOSIT/VAULT_WITHDRAW
            // legs fail isFamilyEquivalentCustodyTransfer and lose basis (D4 root cause).
            Map.entry("FUSDC", FAMILY_USDC),
            Map.entry("MCUSDC", FAMILY_USDC),
            Map.entry("GTUSDCC", FAMILY_USDC),
            Map.entry("RE7USDC", FAMILY_USDC),
            Map.entry("SOUSDC", FAMILY_USDC),
            // USDT family
            Map.entry("USDT", FAMILY_USDT),
            Map.entry("USDT0", FAMILY_USDT),
            Map.entry("USD₮0", FAMILY_USDT),
            Map.entry("EUSDT", FAMILY_USDT),
            Map.entry("EUSDT0", FAMILY_USDT),
            Map.entry("FUSDT", FAMILY_USDT),
            Map.entry("SOUSDT", FAMILY_USDT),
            Map.entry("VBUSDT", FAMILY_USDT),
            // DEUSD / USDE / WSTUSR families
            Map.entry("DEUSD", FAMILY_DEUSD),
            Map.entry("EDEUSD", FAMILY_DEUSD),
            Map.entry("USDE", FAMILY_USDE),
            Map.entry("USDE0", FAMILY_USDE),
            Map.entry("EWEETH", FAMILY_ETH),
            Map.entry("EWSTUSR", FAMILY_WSTUSR),
            Map.entry("WSTUSR", FAMILY_WSTUSR),
            // AVAX family
            Map.entry("AVAX", FAMILY_AVAX),
            Map.entry("WAVAX", FAMILY_AVAX),
            Map.entry("SAVAX", FAMILY_AVAX),
            Map.entry("AAVAWAVAX", FAMILY_AVAX),
            Map.entry("AAVASAVAX", FAMILY_AVAX),
            // MNT family
            Map.entry("MNT", FAMILY_MNT),
            Map.entry("WMNT", FAMILY_MNT),
            Map.entry("AMANMNT", FAMILY_MNT),
            Map.entry("AMANWMNT", FAMILY_MNT)
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
        // Cycle/5 N6: Aave (and compatible) receipt tokens share underlying family across chains.
        String lendingLifecycle = LendingAssetSymbolSupport.lendingReceiptLifecycleUnderlying(symbol);
        if (lendingLifecycle != null && !lendingLifecycle.isBlank() && !"UNKNOWN".equals(lendingLifecycle)) {
            String familyIdentity = SYMBOL_FAMILIES.get(lendingLifecycle);
            if (familyIdentity != null) {
                return familyIdentity;
            }
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
