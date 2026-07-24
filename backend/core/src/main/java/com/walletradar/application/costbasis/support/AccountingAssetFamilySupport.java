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
    public static final String FAMILY_LP_RECEIPT = "FAMILY:LP_RECEIPT";
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
                || symbol.endsWith("-LP")
                // ADR-081 / ADR-023 D2: Pendle LP tokens (and their eqb/pnp staked wrappers) follow the
                // deterministic `-LPT` naming convention (e.g. PENDLE-LPT, eqbPENDLE-LPT, <market>-LPT).
                // This is a protocol convention, not a curated per-token bucket. The plain PENDLE
                // governance/reward token does not end in `-LPT` and stays a priced spot asset.
                || symbol.endsWith("-LPT");
    }

    /**
     * ADR-080 / ADR-081: identity-driven LP-receipt recognition. A holding is an LP receipt when its
     * resolved accounting family is {@link #FAMILY_LP_RECEIPT} — set at classification/replay when the
     * asset participates in an LP correlation (the durable identity/flag route, robust to novel receipt
     * symbols) — falling back to the continuity identity recomputed from the symbol/contract for
     * callers that do not hold a persisted family. Recognition is by <em>identity</em>, never by an
     * ad-hoc broadened symbol-suffix heuristic keyed on real symbol spelling.
     */
    public static boolean isLpReceiptFamilyIdentity(String familyIdentity) {
        return FAMILY_LP_RECEIPT.equals(familyIdentity);
    }

    /**
     * ADR-080 / ADR-081 (C7): whether a dashboard holding row is an LP receipt and must be excluded
     * from the priced spot-asset surface. Consults the resolved/persisted family identity first (so a
     * receipt with a novel symbol routed via its LP correlationId is still excluded), then the
     * continuity identity recomputed from the symbol/contract.
     */
    public static boolean isLpReceiptHolding(String familyIdentity, String assetSymbol, String assetContract) {
        if (isLpReceiptFamilyIdentity(familyIdentity)) {
            return true;
        }
        return FAMILY_LP_RECEIPT.equals(continuityIdentity(assetSymbol, assetContract));
    }

    /**
     * Whether a ledger point may participate in family move-basis timeline quantity/AVCO
     * aggregation for the requested family page.
     * <p>
     * C2 tokens render on their own per-asset family page; only C1 members aggregate under
     * {@code FAMILY:ETH} (ADR-054 / ADR-045).
     */
    public static boolean includeInSpotFamilyTimelineAggregation(String familyIdentity, String assetSymbol) {
        return includeInSpotFamilyTimelineAggregation(familyIdentity, assetSymbol, null);
    }

    /**
     * Persisted-family aware overload. Callers that hold the ledger point pass its authoritative
     * {@code accountingFamilyIdentity} so raw-contract families are never silently dropped.
     * <p>
     * The legacy symbol-only recompute ({@link #continuityIdentity(String, String)} with a
     * {@code null} contract) discards the contract, so any asset whose family is keyed on its
     * contract — native TON persisted as {@code toncoin}, SPL memecoins keyed on their mint, TON
     * jettons keyed on the jetton address — never equalled the requested family and had its entire
     * move-basis timeline filtered out. The point's persisted family is contract-aware and is the
     * value the points were already loaded by, so it is authoritative here; the symbol recompute is
     * kept only as a fallback for callers that do not supply it.
     */
    public static boolean includeInSpotFamilyTimelineAggregation(
            String familyIdentity, String assetSymbol, String persistedFamilyIdentity) {
        if (isLpReceiptSymbol(assetSymbol)) {
            return false;
        }
        String effectiveFamily = (persistedFamilyIdentity != null && !persistedFamilyIdentity.isBlank())
                ? persistedFamilyIdentity
                : continuityIdentity(assetSymbol, null);
        if (effectiveFamily == null || !effectiveFamily.equals(familyIdentity)) {
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
