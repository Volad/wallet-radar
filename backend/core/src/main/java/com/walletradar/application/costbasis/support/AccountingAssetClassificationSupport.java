package com.walletradar.application.costbasis.support;

import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

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
            // AVAX / SOL / MNT / TON native wrappers (not staked derivatives)
            "AVAX", "WAVAX", "AAVAWAVAX",
            "SOL",
            "TON",
            "MNT", "WMNT", "AMANMNT", "AMANWMNT",
            // Stable / other families already 1:1 within family
            "USDC", "USDBC", "AAVAUSDC", "AMANUSDC", "AARBUSDC", "AETHUSDC", "ABASUSDC", "AOPTUSDC", "AZKSUSDC",
            "VBUSDC", "EUSDC", "EEUSDC", "FUSDC", "MCUSDC", "GTUSDCC", "RE7USDC", "SOUSDC",
            "USDT", "USDT0", "USD₮0", "EUSDT", "EUSDT0", "FUSDT", "SOUSDT", "VBUSDT",
            "DEUSD", "EDEUSD", "USDE", "USDE0",
            "EWSTUSR", "WSTUSR",
            "ARB", "AARBARB",
            // ZK family: AZKSZK is the Aave-zkSync aToken, a 1:1 redeemable receipt of native ZK
            // (mirrors ARB/AARBARB) — folds the lending receipt into the ZK spot family rather than
            // stranding it on its raw aToken contract.
            "ZK", "AZKSZK",
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
            Map.entry("TON", "FAMILY:TON"),
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
            Map.entry("ZK", "FAMILY:ZK"),
            Map.entry("AZKSZK", "FAMILY:ZK"),
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

    /** ADR-083 RC-2: trailing Pendle maturity suffix ({@code -DDMONYYYY}), anchored to end. */
    private static final java.util.regex.Pattern MATURITY_SUFFIX =
            java.util.regex.Pattern.compile("-\\d{1,2}[A-Z]{3}\\d{4}$");

    /** ADR-083: staking cluster identities used by cluster-carry (PnL=0 intra-cluster conversions). */
    private static final String CLUSTER_ETH_STAKING = "CLUSTER:ETH_STAKING";
    private static final String CLUSTER_SOL_STAKING = "CLUSTER:SOL_STAKING";
    private static final String CLUSTER_AVAX_STAKING = "CLUSTER:AVAX_STAKING";

    /**
     * ADR-083: single source of truth for staking-cluster membership — {@code FAMILY:*} accounting
     * identity → cluster. Both normalization ({@link #normalizationClusterForSymbol(String)}) and
     * replay carry-vs-realize ({@link #isIntraClusterConversion(NormalizedTransaction)}) resolve
     * cluster membership through this table so they cannot drift. Extending membership means adding
     * a family here (never editing the C1/C2 identity sets), which keeps {@code accountingFamilyIdentity}
     * on ledger points stable (auditor R4 blast-radius containment).
     */
    private static final Map<String, String> FAMILY_TO_CLUSTER = Map.ofEntries(
            // ETH staking cluster
            Map.entry("FAMILY:ETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:METH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:STETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:WSTETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:RETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:CBETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:OSETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:EETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:WEETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:EWEETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:EWETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:EZETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:RSETH", CLUSTER_ETH_STAKING),
            Map.entry("FAMILY:YVVBETH", CLUSTER_ETH_STAKING),
            // SOL staking cluster
            Map.entry("FAMILY:SOL", CLUSTER_SOL_STAKING),
            Map.entry("FAMILY:BBSOL", CLUSTER_SOL_STAKING),
            // AVAX staking cluster
            Map.entry("FAMILY:AVAX", CLUSTER_AVAX_STAKING),
            Map.entry("FAMILY:SAVAX", CLUSTER_AVAX_STAKING)
    );

    /**
     * ADR-083: supplemental cluster membership for staking derivatives whose continuity family
     * identity resolves to a <em>raw contract / mint address</em> (they sit outside the C1/C2
     * registry, so the {@code FAMILY:*} table above cannot key them). Contract-first resolution
     * ({@link #stakingClusterForFlow(String, String)}) runs first; this symbol map is the explicit,
     * reviewable fallback for those members only. Keyed on {@link #classificationSymbol(String)}.
     */
    private static final Map<String, String> SUPPLEMENTAL_CLUSTER_BY_SYMBOL = Map.ofEntries(
            // SOL LSTs keyed on their SPL mint (not in C1/C2)
            Map.entry("MSOL", CLUSTER_SOL_STAKING),
            Map.entry("VSOL", CLUSTER_SOL_STAKING),
            Map.entry("BSOL", CLUSTER_SOL_STAKING),
            Map.entry("BBSOL", CLUSTER_SOL_STAKING),
            Map.entry("JITOSOL", CLUSTER_SOL_STAKING),
            // Pendle principal tokens keyed on their PT contract (not in C1/C2)
            Map.entry("PT-CMETH", CLUSTER_ETH_STAKING),
            Map.entry("PT-ETH", CLUSTER_ETH_STAKING),
            // Aave aAvaSAVAX (also resolved via FAMILY:SAVAX supplemental, kept here for symbol-only paths)
            Map.entry("AAVASAVAX", CLUSTER_AVAX_STAKING)
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

    /**
     * D1: {@code true} when this row is a cross-canonical <b>staking/vault identity change</b> — a
     * staking/vault deposit or withdraw whose principal legs dispose one canonical asset and acquire
     * a distinct one (e.g. ETH → mETH, or a vault deposit minting a differently-identified share).
     * Same-family carries (e.g. mETH → cmETH, both {@code FAMILY:METH}) return {@code false}.
     *
     * <p>Single source of truth used by normalization to stamp
     * {@link NormalizedTransaction#getCrossCanonicalStakingConversion()}, which the pricing layer then
     * reads to force market pricing on both principal legs (ADR-054 §9). No symbol/contract list is
     * hardcoded here beyond the existing C1/C2 identity registry.</p>
     */
    public static boolean isCrossCanonicalStakingVaultConversion(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() == null) {
            return false;
        }
        return switch (transaction.getType()) {
            case STAKING_DEPOSIT, STAKING_WITHDRAW, VAULT_DEPOSIT, VAULT_WITHDRAW ->
                    hasCrossCanonicalIdentityPrincipalPair(transaction);
            default -> false;
        };
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

    /**
     * ADR-083: cluster identity for a {@code FAMILY:*} accounting family, or {@code null} for any
     * family not in a staking cluster. Single source of truth ({@link #FAMILY_TO_CLUSTER}) shared by
     * normalization and replay carry decisions.
     */
    public static String clusterForFamilyIdentity(String familyIdentity) {
        if (familyIdentity == null || familyIdentity.isBlank()) {
            return null;
        }
        return FAMILY_TO_CLUSTER.get(familyIdentity);
    }

    /**
     * ADR-083: contract-first staking-cluster resolution for a flow's asset. Resolves the flow's
     * accounting family via {@link AccountingAssetFamilySupport#continuityIdentity(String, String)}
     * (contract-first, LP-receipt-aware) then maps family → cluster; falls back to the explicit
     * {@link #SUPPLEMENTAL_CLUSTER_BY_SYMBOL} map only for staking members whose family identity is a
     * raw contract/mint (outside the C1/C2 registry). Returns {@code null} for any non-cluster asset
     * (fiat/stable/BTC/other, LP receipts, unmapped instruments) — the fail-safe that keeps
     * cluster↔non-cluster and cross-cluster moves on the realize path.
     */
    public static String stakingClusterForFlow(String assetSymbol, String assetContract) {
        String familyIdentity = AccountingAssetFamilySupport.continuityIdentity(assetSymbol, assetContract);
        String byFamily = clusterForFamilyIdentity(familyIdentity);
        if (byFamily != null) {
            return byFamily;
        }
        String base = classificationSymbol(assetSymbol);
        if (base.isBlank()) {
            return null;
        }
        String bySymbol = SUPPLEMENTAL_CLUSTER_BY_SYMBOL.get(base);
        if (bySymbol != null) {
            return bySymbol;
        }
        // ADR-083 RC-2: Pendle principal tokens carry a maturity suffix (e.g. PT-CMETH-18SEP2025) that
        // classificationSymbol does not strip, so the SUPPLEMENTAL_CLUSTER_BY_SYMBOL key (PT-CMETH)
        // misses. Retry on the maturity-stripped base. Scoped to cluster resolution only so pool /
        // accounting-family identity (which must stay maturity-specific) is unaffected.
        String maturityStripped = stripMaturitySuffix(base);
        return maturityStripped.equals(base) ? null : SUPPLEMENTAL_CLUSTER_BY_SYMBOL.get(maturityStripped);
    }

    /**
     * ADR-083 replay predicate: {@code true} when a conversion is an <b>intra-cluster</b> form change
     * that must carry basis (PnL=0) rather than realize. Requirements:
     * <ul>
     *   <li>type is a conversion type ({@code STAKING_DEPOSIT}/{@code STAKING_WITHDRAW}/
     *       {@code VAULT_DEPOSIT}/{@code VAULT_WITHDRAW}/{@code SWAP});</li>
     *   <li>principals contain both an outbound (−) and an inbound (+) leg;</li>
     *   <li>every principal leg resolves (contract-first) to the <b>same non-null</b> staking cluster;</li>
     *   <li>no principal leg is non-cluster (a fiat/stable/BTC/other or second-cluster principal ⇒ realize).</li>
     * </ul>
     * For {@code SWAP} the conversion must additionally be genuinely cross-canonical
     * ({@link #hasCrossCanonicalIdentityPrincipalPair(NormalizedTransaction)}): same-canonical-identity
     * swaps (e.g. WETH↔ETH, WAVAX↔AVAX) stay on the family-equivalent same-family swap path, which
     * carries basis with its own dust-fragment ratio guard. Single-pass and type-gated for hot-path
     * SWAP cost.
     */
    public static boolean isIntraClusterConversion(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getType() == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean typeGated = switch (transaction.getType()) {
            case STAKING_DEPOSIT, STAKING_WITHDRAW, VAULT_DEPOSIT, VAULT_WITHDRAW, SWAP -> true;
            default -> false;
        };
        if (!typeGated) {
            return false;
        }
        // ADR-083 RC-1: exclude only a *provably same-canonical-token* swap (WETH↔ETH, WAVAX↔AVAX),
        // which belongs on the same-family swap path. The former C1/C2-only pre-gate
        // (hasCrossCanonicalIdentityPrincipalPair) rejected any swap whose leg identity is unknown to
        // the registry (Pendle PT, Solana SPL LSTs, Aave aTokens) *before* the contract-first cluster
        // test could run, silently realizing genuine intra-cluster conversions. We now defer to the
        // per-leg cluster loop below whenever the same-token identity cannot be proven.
        if (transaction.getType() == NormalizedTransactionType.SWAP
                && isSameCanonicalTokenIdentitySwap(transaction)) {
            return false;
        }
        String cluster = null;
        boolean hasOutbound = false;
        boolean hasInbound = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isPrincipalFlow(flow)) {
                continue;
            }
            String flowCluster = stakingClusterForFlow(flow.getAssetSymbol(), flow.getAssetContract());
            if (flowCluster == null) {
                return false;
            }
            if (cluster == null) {
                cluster = flowCluster;
            } else if (!cluster.equals(flowCluster)) {
                return false;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                hasOutbound = true;
            } else if (flow.getQuantityDelta().signum() > 0) {
                hasInbound = true;
            }
        }
        return cluster != null && hasOutbound && hasInbound;
    }

    /**
     * ADR-083 RC-1: {@code true} only when a swap is a <b>provably same-canonical-token identity</b>
     * move — every principal leg resolves (via the C1/C2 {@link #canonicalTokenIdentity}) to one and
     * the same non-null identity, with both an outbound and an inbound leg (e.g. WETH→ETH, WAVAX→AVAX).
     * These belong on the same-family swap path. When any principal leg's canonical identity is unknown
     * (Pendle PT / Solana SPL LST / Aave aToken outside the registry), we cannot prove a same-token
     * move and return {@code false}, letting the contract-first cluster test decide carry-vs-realize.
     */
    private static boolean isSameCanonicalTokenIdentitySwap(NormalizedTransaction transaction) {
        String shared = null;
        boolean hasOutbound = false;
        boolean hasInbound = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!isPrincipalFlow(flow)) {
                continue;
            }
            String identity = canonicalTokenIdentity(flow.getAssetSymbol(), flow.getAssetContract());
            if (identity == null || identity.isBlank()) {
                return false;
            }
            if (shared == null) {
                shared = identity;
            } else if (!shared.equals(identity)) {
                return false;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                hasOutbound = true;
            } else if (flow.getQuantityDelta().signum() > 0) {
                hasInbound = true;
            }
        }
        return shared != null && hasOutbound && hasInbound;
    }

    public static String normalizationClusterForSymbol(String assetSymbol) {
        String base = classificationSymbol(assetSymbol);
        if (base.isBlank()) {
            return null;
        }
        // ADR-083: symbol-only family resolution (this method's historical contract), then map
        // through the single FAMILY→CLUSTER table so normalization and replay agree.
        String family = null;
        if (isC2DistinctAsset(base)) {
            family = C2_CONTINUITY_FAMILY.get(base);
        } else if (isC1SameAsset(base)) {
            family = C1_UNDERLYING_FAMILY.get(base);
        }
        String cluster = clusterForFamilyIdentity(family);
        if (cluster != null) {
            return cluster;
        }
        return SUPPLEMENTAL_CLUSTER_BY_SYMBOL.get(base);
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

    /**
     * ADR-083 RC-2: strips a trailing Pendle-style maturity segment ({@code -DDMONYYYY}, e.g.
     * {@code -18SEP2025}) from a normalized symbol so {@code PT-CMETH-18SEP2025} → {@code PT-CMETH}.
     * The pattern is date-shaped (1–2 digits, 3 letters, 4 digits) and anchored to the end, so it does
     * not touch ordinary hyphenated symbols. Returns the input unchanged when no maturity suffix is
     * present.
     */
    private static String stripMaturitySuffix(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return symbol;
        }
        return MATURITY_SUFFIX.matcher(symbol).replaceAll("");
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
