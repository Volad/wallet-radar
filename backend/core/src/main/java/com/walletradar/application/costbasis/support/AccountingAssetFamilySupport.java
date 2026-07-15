package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.lending.application.LendingAssetSymbolSupport;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared accounting-family identity contract for continuity-preserving replay.
 * C1/C2 boundary is owned by {@link AccountingAssetClassificationSupport} (ADR-054).
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
    private static final String FAMILY_LP_RECEIPT = "FAMILY:LP_RECEIPT";
    private static final String FAMILY_ETH_IDENTITY = "FAMILY:ETH";

    static final Map<String, String> SYMBOL_FAMILIES = Map.ofEntries(
            // BTC family (C1 only)
            Map.entry("BTC", FAMILY_BTC),
            Map.entry("WBTC", FAMILY_BTC),
            Map.entry("AARBWBTC", FAMILY_BTC),
            Map.entry("AETHWBTC", FAMILY_BTC),
            Map.entry("ALINWBTC", FAMILY_BTC),
            Map.entry("AMANWBTC", FAMILY_BTC),
            Map.entry("AZKSWBTC", FAMILY_BTC),
            Map.entry("ABASWBTC", FAMILY_BTC),
            Map.entry("AOPTWBTC", FAMILY_BTC),
            // ETH family (C1 only — C2 staked derivatives have own per-token families)
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
            // C2 ETH-derivatives — per-token families (ADR-054)
            Map.entry("STETH", "FAMILY:STETH"),
            Map.entry("WSTETH", "FAMILY:WSTETH"),
            Map.entry("RETH", "FAMILY:RETH"),
            Map.entry("CBETH", "FAMILY:CBETH"),
            Map.entry("EETH", "FAMILY:EETH"),
            Map.entry("WEETH", "FAMILY:WEETH"),
            Map.entry("EWEETH", "FAMILY:EWEETH"),
            Map.entry("EWETH", "FAMILY:EWETH"),
            Map.entry("EZETH", "FAMILY:EZETH"),
            Map.entry("RSETH", "FAMILY:RSETH"),
            Map.entry("OSETH", "FAMILY:OSETH"),
            Map.entry("METH", "FAMILY:METH"),
            Map.entry("CMETH", "FAMILY:METH"),
            Map.entry("YVVBETH", "FAMILY:YVVBETH"),
            // SOL family (native only; BBSOL is C2)
            Map.entry("SOL", FAMILY_SOL),
            Map.entry("BBSOL", "FAMILY:BBSOL"),
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
            Map.entry("EWSTUSR", FAMILY_WSTUSR),
            Map.entry("WSTUSR", FAMILY_WSTUSR),
            // AVAX family (native only; sAVAX is C2)
            Map.entry("AVAX", FAMILY_AVAX),
            Map.entry("WAVAX", FAMILY_AVAX),
            Map.entry("SAVAX", "FAMILY:SAVAX"),
            Map.entry("AAVAWAVAX", FAMILY_AVAX),
            Map.entry("AAVASAVAX", "FAMILY:SAVAX"),
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
        if (CanonicalAssetCatalog.isConfusableSymbol(assetSymbol)) {
            String contract = normalizeContract(assetContract);
            if (contract != null) {
                return contract;
            }
            return symbol.isBlank() ? null : "SYMBOL:" + symbol;
        }
        if (isLpReceiptSymbol(symbol)) {
            return FAMILY_LP_RECEIPT;
        }
        String registryIdentity = AccountingAssetClassificationSupport.continuityFamilyIdentity(assetSymbol, assetContract);
        if (registryIdentity != null) {
            return registryIdentity;
        }
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
        String normalized = contract.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NATIVE:") || upper.startsWith("SYMBOL:") || upper.startsWith("FAMILY:")) {
            return upper;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isLpReceiptSymbol(String assetSymbol) {
        String symbol = normalizeSymbol(assetSymbol);
        if (symbol.isBlank()) {
            return false;
        }
        return symbol.startsWith("LP-RECEIPT:")
                || symbol.contains("-LP-")
                || symbol.endsWith("-LP");
    }

    /**
     * Whether a ledger point may participate in family move-basis timeline quantity/AVCO
     * aggregation for the requested family page.
     * <p>
     * C2 tokens render on their own per-asset family page; only C1 members aggregate under
     * {@code FAMILY:ETH} (ADR-054 / ADR-045).
     */
    public static boolean includeInSpotFamilyTimelineAggregation(String familyIdentity, String assetSymbol) {
        if (isLpReceiptSymbol(assetSymbol)) {
            return false;
        }
        String symbolFamily = continuityIdentity(assetSymbol, null);
        if (symbolFamily == null || !symbolFamily.equals(familyIdentity)) {
            return false;
        }
        if (FAMILY_ETH_IDENTITY.equals(familyIdentity)
                && AccountingAssetClassificationSupport.isC2DistinctAsset(assetSymbol)) {
            return false;
        }
        return true;
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
            return "FAMILY:EWEETH";
        }
        if (matchesEulerIndexedReceipt(symbol, "EWETH-")) {
            return "FAMILY:EWETH";
        }
        if (matchesEulerIndexedReceipt(symbol, "EWSTUSR-")) {
            return FAMILY_WSTUSR;
        }
        if (matchesEulerIndexedReceipt(symbol, "EDEUSD-")) {
            return FAMILY_DEUSD;
        }
        String lendingLifecycle = LendingAssetSymbolSupport.lendingReceiptLifecycleUnderlying(symbol);
        if (lendingLifecycle != null && !lendingLifecycle.isBlank() && !"UNKNOWN".equals(lendingLifecycle)) {
            String familyIdentity = SYMBOL_FAMILIES.get(lendingLifecycle);
            if (familyIdentity != null) {
                return familyIdentity;
            }
            String registryFamily = AccountingAssetClassificationSupport.continuityFamilyIdentity(lendingLifecycle, null);
            if (registryFamily != null) {
                return registryFamily;
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
