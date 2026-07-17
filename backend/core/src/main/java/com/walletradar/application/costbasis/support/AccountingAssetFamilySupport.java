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

    private static final String FAMILY_USDC = "FAMILY:USDC";
    private static final String FAMILY_USDT = "FAMILY:USDT";
    private static final String FAMILY_DEUSD = "FAMILY:DEUSD";
    private static final String FAMILY_WSTUSR = "FAMILY:WSTUSR";
    private static final String FAMILY_LP_RECEIPT = "FAMILY:LP_RECEIPT";
    private static final String FAMILY_ETH_IDENTITY = "FAMILY:ETH";

    /**
     * Supplemental accounting-family entries that are intentionally NOT owned by the C1/C2 registry
     * (ADR-060 / Wave W9). Every other symbol→family mapping is the single responsibility of
     * {@link AccountingAssetClassificationSupport} — which {@link #continuityIdentity(String, String)}
     * consults <em>first</em>, so a registry-classified symbol never reaches this fallback. Prior to
     * W9 an ~80-entry {@code SYMBOL_FAMILIES} map duplicated the registry; it was proven subsumed for
     * every key except the one below and removed.
     *
     * <p>{@code AAVASAVAX} (Aave aAvaSAVAX) is a 1:1 receipt of sAVAX and shares its
     * {@code FAMILY:SAVAX} pool, but is not currently classified C1/C2. It is resolved here — after
     * the registry, <em>before</em> {@link #inferredFamilyIdentity(String)} — to preserve exact
     * behavior and to defeat the lending-inference reroute that would otherwise mis-send it to
     * {@code FAMILY:AVAX} (the SAVAX→AVAX lifecycle mapping). Promoting it to a C1 receipt is a
     * reviewer-gated follow-up (ADR-060 §B3c).</p>
     */
    static final Map<String, String> SUPPLEMENTAL_FAMILIES = Map.of(
            "AAVASAVAX", "FAMILY:SAVAX"
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
        String familyIdentity = SUPPLEMENTAL_FAMILIES.get(symbol);
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
            String familyIdentity = SUPPLEMENTAL_FAMILIES.get(lendingLifecycle);
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
