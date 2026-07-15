package com.walletradar.application.costbasis.support;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Declarative C1/C2 asset classification registry (ADR-054).
 * <p>
 * C1 — same underlying claim, 1:1 redeemable at fixed rate → share underlying family, carry basis.
 * C2 — distinct market asset (staked / value-accruing / ERC-4626 receipt) → own per-token family, realize P&amp;L on identity change.
 */
public final class AccountingAssetClassificationSupport {

    private static final String FAMILY_PREFIX = "FAMILY:";

    /**
     * C1 — same-asset custody representations sharing the underlying family identity.
     */
    private static final Set<String> C1_SAME_ASSET = Set.of(
            // ETH C1
            "ETH", "WETH", "AWETH", "AETHWETH", "AARBWETH", "ALINWETH", "AMANWETH", "AZKSWETH", "ABASWETH", "AOPTWETH",
            "VBETH",
            // BTC C1
            "BTC", "WBTC", "AARBWBTC", "AETHWBTC", "ALINWBTC", "AMANWBTC", "AZKSWBTC", "ABASWBTC", "AOPTWBTC",
            // AVAX / SOL / MNT native wrappers (not staked derivatives)
            "AVAX", "WAVAX", "AAVAWAVAX",
            "SOL",
            "MNT", "WMNT", "AMANMNT", "AMANWMNT",
            // Stable / other families already 1:1 within family
            "USDC", "USDBC", "AAVAUSDC", "AMANUSDC", "AARBUSDC", "AETHUSDC", "ABASUSDC", "AOPTUSDC", "AZKSUSDC",
            "VBUSDC", "EUSDC", "EEUSDC", "FUSDC", "MCUSDC", "GTUSDCC", "RE7USDC", "SOUSDC",
            "USDT", "USDT0", "USD₮0", "EUSDT", "EUSDT0", "FUSDT", "SOUSDT", "VBUSDT",
            "DEUSD", "EDEUSD", "USDE", "USDE0",
            "EWSTUSR", "WSTUSR",
            "ARB", "AARBARB",
            // METH family: CMETH is a 1:1 Bybit-issued receipt for METH staking deposits and
            // shares the METH cost-basis pool (ADR-054 C1 — not a separately-priced C2 asset).
            "CMETH"
    );

    /**
     * C2 — distinct market assets with own per-token continuity family.
     */
    private static final Set<String> C2_DISTINCT_ASSET = Set.of(
            // ETH-derivative C2
            "STETH", "WSTETH", "RETH", "CBETH", "METH", "OSETH",
            "EETH", "WEETH", "EWEETH", "EWETH", "EZETH", "RSETH", "YVVBETH",
            // Non-ETH C2 (A-12)
            "SAVAX", "BBSOL"
    );

    private static final Map<String, String> C1_UNDERLYING_FAMILY = Map.ofEntries(
            Map.entry("ETH", "FAMILY:ETH"),
            Map.entry("WETH", "FAMILY:ETH"),
            Map.entry("AWETH", "FAMILY:ETH"),
            Map.entry("AETHWETH", "FAMILY:ETH"),
            Map.entry("AARBWETH", "FAMILY:ETH"),
            Map.entry("ALINWETH", "FAMILY:ETH"),
            Map.entry("AMANWETH", "FAMILY:ETH"),
            Map.entry("AZKSWETH", "FAMILY:ETH"),
            Map.entry("ABASWETH", "FAMILY:ETH"),
            Map.entry("AOPTWETH", "FAMILY:ETH"),
            Map.entry("VBETH", "FAMILY:ETH"),
            Map.entry("BTC", "FAMILY:BTC"),
            Map.entry("WBTC", "FAMILY:BTC"),
            Map.entry("AARBWBTC", "FAMILY:BTC"),
            Map.entry("AETHWBTC", "FAMILY:BTC"),
            Map.entry("ALINWBTC", "FAMILY:BTC"),
            Map.entry("AMANWBTC", "FAMILY:BTC"),
            Map.entry("AZKSWBTC", "FAMILY:BTC"),
            Map.entry("ABASWBTC", "FAMILY:BTC"),
            Map.entry("AOPTWBTC", "FAMILY:BTC"),
            Map.entry("AVAX", "FAMILY:AVAX"),
            Map.entry("WAVAX", "FAMILY:AVAX"),
            Map.entry("AAVAWAVAX", "FAMILY:AVAX"),
            Map.entry("SOL", "FAMILY:SOL"),
            Map.entry("MNT", "FAMILY:MNT"),
            Map.entry("WMNT", "FAMILY:MNT"),
            Map.entry("AMANMNT", "FAMILY:MNT"),
            Map.entry("AMANWMNT", "FAMILY:MNT"),
            Map.entry("USDC", "FAMILY:USDC"),
            Map.entry("USDBC", "FAMILY:USDC"),
            Map.entry("AAVAUSDC", "FAMILY:USDC"),
            Map.entry("AMANUSDC", "FAMILY:USDC"),
            Map.entry("AARBUSDC", "FAMILY:USDC"),
            Map.entry("AETHUSDC", "FAMILY:USDC"),
            Map.entry("ABASUSDC", "FAMILY:USDC"),
            Map.entry("AOPTUSDC", "FAMILY:USDC"),
            Map.entry("AZKSUSDC", "FAMILY:USDC"),
            Map.entry("VBUSDC", "FAMILY:USDC"),
            Map.entry("EUSDC", "FAMILY:USDC"),
            Map.entry("EEUSDC", "FAMILY:USDC"),
            Map.entry("FUSDC", "FAMILY:USDC"),
            Map.entry("MCUSDC", "FAMILY:USDC"),
            Map.entry("GTUSDCC", "FAMILY:USDC"),
            Map.entry("RE7USDC", "FAMILY:USDC"),
            Map.entry("SOUSDC", "FAMILY:USDC"),
            Map.entry("USDT", "FAMILY:USDT"),
            Map.entry("USDT0", "FAMILY:USDT"),
            Map.entry("USD₮0", "FAMILY:USDT"),
            Map.entry("EUSDT", "FAMILY:USDT"),
            Map.entry("EUSDT0", "FAMILY:USDT"),
            Map.entry("FUSDT", "FAMILY:USDT"),
            Map.entry("SOUSDT", "FAMILY:USDT"),
            Map.entry("VBUSDT", "FAMILY:USDT"),
            Map.entry("DEUSD", "FAMILY:DEUSD"),
            Map.entry("EDEUSD", "FAMILY:DEUSD"),
            Map.entry("USDE", "FAMILY:USDE"),
            Map.entry("USDE0", "FAMILY:USDE"),
            Map.entry("EWSTUSR", "FAMILY:WSTUSR"),
            Map.entry("WSTUSR", "FAMILY:WSTUSR"),
            Map.entry("ARB", "FAMILY:ARB"),
            Map.entry("AARBARB", "FAMILY:ARB"),
            Map.entry("CMETH", "FAMILY:METH")
    );

    private static final Map<String, String> C2_CONTINUITY_FAMILY = Map.ofEntries(
            Map.entry("STETH", "FAMILY:STETH"),
            Map.entry("WSTETH", "FAMILY:WSTETH"),
            Map.entry("RETH", "FAMILY:RETH"),
            Map.entry("CBETH", "FAMILY:CBETH"),
            Map.entry("METH", "FAMILY:METH"),
            Map.entry("OSETH", "FAMILY:OSETH"),
            Map.entry("EETH", "FAMILY:EETH"),
            Map.entry("WEETH", "FAMILY:WEETH"),
            Map.entry("EWEETH", "FAMILY:EWEETH"),
            Map.entry("EWETH", "FAMILY:EWETH"),
            Map.entry("EZETH", "FAMILY:EZETH"),
            Map.entry("RSETH", "FAMILY:RSETH"),
            Map.entry("YVVBETH", "FAMILY:YVVBETH"),
            Map.entry("SAVAX", "FAMILY:SAVAX"),
            Map.entry("BBSOL", "FAMILY:BBSOL")
    );

    private AccountingAssetClassificationSupport() {
    }

    public static Set<String> c1SameAssetSymbols() {
        return C1_SAME_ASSET;
    }

    public static Set<String> c2DistinctAssetSymbols() {
        return C2_DISTINCT_ASSET;
    }

    public static boolean isC1SameAsset(String assetSymbol) {
        return C1_SAME_ASSET.contains(classificationSymbol(assetSymbol));
    }

    public static boolean isC2DistinctAsset(String assetSymbol) {
        return C2_DISTINCT_ASSET.contains(classificationSymbol(assetSymbol));
    }

    /**
     * Canonical token identity for carry-vs-realize gating (ADR-054).
     * Contract-first for confusable symbols; C2 → {@code FAMILY:<TOKEN>}; C1 → underlying family.
     */
    public static String canonicalTokenIdentity(String assetSymbol, String assetContract) {
        if (CanonicalAssetCatalog.isConfusableSymbol(assetSymbol)) {
            String contract = normalizeContract(assetContract);
            if (contract != null) {
                return contract;
            }
            String symbol = normalizeSymbol(assetSymbol);
            return symbol.isBlank() ? null : "SYMBOL:" + symbol;
        }
        String baseSymbol = classificationSymbol(assetSymbol);
        if (baseSymbol.isBlank()) {
            return null;
        }
        if (isC2DistinctAsset(baseSymbol)) {
            return C2_CONTINUITY_FAMILY.get(baseSymbol);
        }
        if (isC1SameAsset(baseSymbol)) {
            return C1_UNDERLYING_FAMILY.get(baseSymbol);
        }
        return null;
    }

    /**
     * L1 continuity family identity used by replay carry matching and read-model rollup.
     */
    public static String continuityFamilyIdentity(String assetSymbol, String assetContract) {
        if (CanonicalAssetCatalog.isConfusableSymbol(assetSymbol)) {
            String contract = normalizeContract(assetContract);
            if (contract != null) {
                return contract;
            }
            String symbol = normalizeSymbol(assetSymbol);
            return symbol.isBlank() ? null : "SYMBOL:" + symbol;
        }
        String baseSymbol = classificationSymbol(assetSymbol);
        if (baseSymbol.isBlank()) {
            return null;
        }
        if (isC2DistinctAsset(baseSymbol)) {
            return C2_CONTINUITY_FAMILY.get(baseSymbol);
        }
        if (isC1SameAsset(baseSymbol)) {
            return C1_UNDERLYING_FAMILY.get(baseSymbol);
        }
        return null;
    }

    /**
     * Returns {@code true} when principal outbound and inbound legs in the transaction carry
     * different canonical token identities (identity change → disposal + acquisition, not REALLOCATE).
     */
    public static boolean hasCrossCanonicalIdentityPrincipalPair(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        java.util.List<String> outboundIdentities = new java.util.ArrayList<>();
        java.util.List<String> inboundIdentities = new java.util.ArrayList<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isPrincipalFlow(flow)) {
                continue;
            }
            String identity = canonicalTokenIdentity(flow.getAssetSymbol(), flow.getAssetContract());
            if (identity == null || identity.isBlank()) {
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                outboundIdentities.add(identity);
            } else if (flow.getQuantityDelta().signum() > 0) {
                inboundIdentities.add(identity);
            }
        }
        if (outboundIdentities.isEmpty() || inboundIdentities.isEmpty()) {
            return false;
        }
        for (String outbound : outboundIdentities) {
            for (String inbound : inboundIdentities) {
                if (!outbound.equals(inbound)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean sharesCanonicalTokenIdentity(
            String leftSymbol,
            String leftContract,
            String rightSymbol,
            String rightContract
    ) {
        String left = canonicalTokenIdentity(leftSymbol, leftContract);
        String right = canonicalTokenIdentity(rightSymbol, rightContract);
        return left != null && left.equals(right);
    }

    /**
     * Normalization-only cluster for fusing liquid-staking conversion legs (Bybit pairers, orphan repair).
     * Replay carry-vs-realize still uses {@link #canonicalTokenIdentity(String, String)} (ADR-054).
     */
    public static boolean sharesLiquidStakingNormalizationCluster(String leftSymbol, String rightSymbol) {
        String leftCluster = normalizationClusterForSymbol(leftSymbol);
        String rightCluster = normalizationClusterForSymbol(rightSymbol);
        return leftCluster != null && leftCluster.equals(rightCluster);
    }

    public static String liquidStakingNormalizationCluster(String leftSymbol, String rightSymbol) {
        String leftCluster = normalizationClusterForSymbol(leftSymbol);
        String rightCluster = normalizationClusterForSymbol(rightSymbol);
        if (leftCluster != null && leftCluster.equals(rightCluster)) {
            return leftCluster;
        }
        return null;
    }

    public static String normalizationClusterForSymbol(String assetSymbol) {
        String base = classificationSymbol(assetSymbol);
        if (base.isBlank()) {
            return null;
        }
        if (isC1SameAsset(base)) {
            String family = C1_UNDERLYING_FAMILY.get(base);
            if ("FAMILY:ETH".equals(family)) {
                return "CLUSTER:ETH_STAKING";
            }
            if ("FAMILY:AVAX".equals(family)) {
                return "CLUSTER:AVAX_STAKING";
            }
            if ("FAMILY:SOL".equals(family)) {
                return "CLUSTER:SOL_STAKING";
            }
            // CMETH is C1(FAMILY:METH) — METH is an ETH staking derivative, so CMETH
            // belongs to the ETH staking normalization cluster alongside METH.
            if ("FAMILY:METH".equals(family)) {
                return "CLUSTER:ETH_STAKING";
            }
        }
        if (isC2DistinctAsset(base)) {
            if ("SAVAX".equals(base)) {
                return "CLUSTER:AVAX_STAKING";
            }
            if ("BBSOL".equals(base)) {
                return "CLUSTER:SOL_STAKING";
            }
            if (C2_CONTINUITY_FAMILY.containsKey(base)) {
                return "CLUSTER:ETH_STAKING";
            }
        }
        return null;
    }

    static String classificationSymbol(String assetSymbol) {
        String normalized = normalizeSymbol(assetSymbol);
        if (normalized.isBlank()) {
            return normalized;
        }
        int dashIndex = normalized.indexOf('-');
        if (dashIndex > 0) {
            String prefix = normalized.substring(0, dashIndex);
            String suffix = normalized.substring(dashIndex + 1);
            if (isEulerIndexedSuffix(suffix) && (C2_DISTINCT_ASSET.contains(prefix) || C1_SAME_ASSET.contains(prefix))) {
                return prefix;
            }
        }
        return normalized;
    }

    static Map<String, String> c2ContinuityFamilies() {
        return C2_CONTINUITY_FAMILY;
    }

    static Map<String, String> c1UnderlyingFamilies() {
        return C1_UNDERLYING_FAMILY;
    }

    private static boolean isPrincipalFlow(NormalizedTransaction.Flow flow) {
        if (flow == null
                || flow.getRole() == null
                || flow.getRole() == NormalizedLegRole.FEE
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.TRANSFER) {
            return true;
        }
        return (flow.getRole() == NormalizedLegRole.SELL && flow.getQuantityDelta().signum() < 0)
                || (flow.getRole() == NormalizedLegRole.BUY && flow.getQuantityDelta().signum() > 0);
    }

    private static boolean isEulerIndexedSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        String normalized = contract.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NATIVE:") || upper.startsWith("SYMBOL:") || upper.startsWith(FAMILY_PREFIX)) {
            return upper;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
