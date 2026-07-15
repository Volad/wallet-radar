package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodyPair;
import com.walletradar.application.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@Slf4j
public class FamilyEquivalentCustodyReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ContinuityCarryService continuityCarryService;
    private final ReplayPendingTransferKeyFactory pendingTransferKeyFactory;

    public FamilyEquivalentCustodyReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ContinuityCarryService continuityCarryService,
            ReplayPendingTransferKeyFactory pendingTransferKeyFactory
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.continuityCarryService = continuityCarryService;
        this.pendingTransferKeyFactory = pendingTransferKeyFactory;
    }

    public SimpleFamilyCustodySelection selectFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || (!isSimpleFamilyEquivalentCustodyType(transaction.getType())
                    && !isSameFamilySwap(transaction))
                || pendingTransferKeyFactory.usesCompositeContinuityBucket(transaction)) {
            return SimpleFamilyCustodySelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        // BUY inbound flows suppressed by isSuppressedAccrualBuy() are tracked separately so they
        // are included in selectedByIndex after pairing — this prevents replayGenericFlowsSkipping
        // from processing them a second time as REALLOCATE_IN and double-counting the cost basis.
        // Observed on Silo Finance LENDING_DEPOSIT: the tx emits a raw TRANSFER of soUSDC (in
        // on-chain raw units, e.g. 199835669) AND a BUY flow of soUSDC at human-readable scale
        // (199.95). FamilyEquivalentCustodyReplayHandler correctly pairs the TRANSFER; without the
        // suppression the BUY would fall through to generic replay and record a second REALLOCATE_IN
        // that inflates AVCO to ~$2/USDC, creating phantom realized losses on every subsequent REPAY.
        List<IndexedFlow> suppressedAccrualFlows = new ArrayList<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (!isPrincipalCandidate(transaction, flow)) {
                if (isSuppressedAccrualBuy(transaction, flow)) {
                    suppressedAccrualFlows.add(indexedFlow);
                }
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            flowsByFamily.computeIfAbsent(continuityIdentity, ignored -> new ArrayList<>()).add(indexedFlow);
        }

        List<SimpleFamilyCustodyPair> pairs = new ArrayList<>();
        Map<Integer, IndexedFlow> selectedByIndex = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexedFlow>> entry : flowsByFamily.entrySet()) {
            String familyIdentity = entry.getKey();
            List<IndexedFlow> familyFlows = entry.getValue();
            List<IndexedFlow> outboundFlows = familyFlows.stream()
                    .filter(flow -> flow.flow().getQuantityDelta().signum() < 0)
                    .toList();
            List<IndexedFlow> inboundFlows = familyFlows.stream()
                    .filter(flow -> flow.flow().getQuantityDelta().signum() > 0)
                    .toList();
            if (outboundFlows.isEmpty() || inboundFlows.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "FAMILY_CUSTODY_PAIR_SKIPPED txId={} type={} family={} outboundCount={} inboundCount={}",
                            transaction.getId(),
                            transaction.getType(),
                            familyIdentity,
                            outboundFlows.size(),
                            inboundFlows.size()
                    );
                }
                continue;
            }

            IndexedFlow outbound;
            if (outboundFlows.size() == 1) {
                outbound = outboundFlows.getFirst();
            } else {
                // Cycle/9 S6: multi-outbound shapes (e.g., Aave V3 withdraw via WETHGateway
                // where WETH is briefly received then unwrapped; or supply where a tiny refund
                // emits a same-asset inbound). Select the outbound whose net delta over the
                // tx is strictly negative — the asset that actually left the wallet. If no
                // single asset dominates, fall back to the largest-absolute-quantity outbound
                // so basis carry still proceeds rather than zeroing.
                outbound = selectPrincipalOutbound(transaction, outboundFlows, inboundFlows);
                if (outbound == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "FAMILY_CUSTODY_NO_PRINCIPAL_OUTBOUND txId={} type={} family={} outboundCount={} inboundCount={}",
                                transaction.getId(),
                                transaction.getType(),
                                familyIdentity,
                                outboundFlows.size(),
                                inboundFlows.size()
                        );
                    }
                    continue;
                }
            }

            IndexedFlow inbound = selectPrincipalInbound(transaction, outbound, inboundFlows);
            if (inbound == null) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "FAMILY_CUSTODY_NO_DISTINCT_INBOUND txId={} type={} family={} outboundAsset={}",
                            transaction.getId(),
                            transaction.getType(),
                            familyIdentity,
                            outbound.flow().getAssetSymbol()
                    );
                }
                continue;
            }
            if (selectedByIndex.containsKey(outbound.index()) || selectedByIndex.containsKey(inbound.index())) {
                continue;
            }
            if (!AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                    outbound.flow().getAssetSymbol(),
                    outbound.flow().getAssetContract(),
                    inbound.flow().getAssetSymbol(),
                    inbound.flow().getAssetContract()
            )) {
                continue;
            }
            pairs.add(new SimpleFamilyCustodyPair(outbound, inbound));
            selectedByIndex.put(outbound.index(), outbound);
            selectedByIndex.put(inbound.index(), inbound);
        }
        // Add suppressed accrual flows to selectedByIndex so replayGenericFlowsSkipping skips them.
        for (IndexedFlow suppressed : suppressedAccrualFlows) {
            selectedByIndex.put(suppressed.index(), suppressed);
        }
        if (pairs.isEmpty() && selectedByIndex.isEmpty()) {
            return SimpleFamilyCustodySelection.empty();
        }
        pairs.sort(java.util.Comparator.comparingInt(pair -> Math.min(pair.outbound().index(), pair.inbound().index())));
        return new SimpleFamilyCustodySelection(pairs, selectedByIndex);
    }

    public void applySelected(
            NormalizedTransaction transaction,
            SimpleFamilyCustodySelection selection,
            ReplayExecutionState replayState
    ) {
        for (SimpleFamilyCustodyPair pair : selection.pairs()) {
            IndexedFlow outbound = pair.outbound();
            IndexedFlow inbound = pair.inbound();

            AssetKey outboundAssetKey = assetSupport.assetKey(transaction, outbound.flow());
            PositionState outboundPosition = replayState.position(outboundAssetKey);
            outboundPosition.setLastEventTimestamp(flowSupport.laterOf(outboundPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            // P0-D: Capture stored AVCO before drain so WRAP/UNWRAP can preserve it even when
            // totalCostBasisUsd is zero (e.g., pending bridge inbound not yet settled).
            BigDecimal preDrainStoredAvco = isWrapOrUnwrap(transaction)
                    ? outboundPosition.perWalletAvco()
                    : null;
            PositionSnapshot outboundBefore = flowSupport.snapshot(outboundPosition);
            CarryTransfer carry = continuityCarryService.removeTransferCarry(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition,
                    replayState.passThroughCorridorPlan(),
                    replayState.reservedPassThroughCarries(),
                    true
            );
            BigDecimal basisRemoved = nonNegative(
                    outboundBefore.totalCostBasisUsd().subtract(
                            outboundPosition.totalCostBasisUsd() == null
                                    ? BigDecimal.ZERO
                                    : outboundPosition.totalCostBasisUsd(),
                            MC
                    )
            );
            carry = continuityCarryService.alignCarryToRemovedBasis(
                    carry,
                    basisRemoved,
                    outboundPosition.assetKey()
            );
            // P0-D: WRAP/UNWRAP must preserve source AVCO from source to destination bucket.
            // If the carry has no AVCO (basis=0) but the position had a stored AVCO before the
            // drain, rebuild the carry using the pre-drain stored AVCO so WETH inherits ETH AVCO.
            if (preDrainStoredAvco != null && preDrainStoredAvco.signum() > 0
                    && (carry.avco() == null || carry.avco().signum() == 0)) {
                BigDecimal movedQty = outbound.flow().getQuantityDelta().abs();
                BigDecimal wrapBasis = movedQty.multiply(preDrainStoredAvco, MC);
                carry = continuityCarryService.buildExplicitCarryTransfer(
                        movedQty, wrapBasis, outboundPosition.assetKey()
                );
            }
            replayState.ledgerPointCollector().record(
                    transaction,
                    outbound.flow(),
                    outbound.index(),
                    outboundPosition.assetKey(),
                    outboundBefore,
                    outboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );

            AssetKey inboundAssetKey = assetSupport.assetKey(transaction, inbound.flow());
            PositionState inboundPosition = replayState.position(inboundAssetKey);
            inboundPosition.setLastEventTimestamp(flowSupport.laterOf(inboundPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot inboundBefore = flowSupport.snapshot(inboundPosition);

            // PROTOCOL_CUSTODY_WITHDRAW with an empty carry means the corresponding deposit either
            // predates the backfill window or was never tracked. Carrying $0 basis forward via
            // REALLOCATE_IN would permanently destroy the cost basis. Instead, treat the returned
            // principal as a fresh ACQUIRE so stablecoin $1/unit logic (or market price for
            // non-stables) can fill the gap. This is scoped exclusively to PROTOCOL_CUSTODY_WITHDRAW
            // to avoid widening the semantics of other custody types (VAULT_WITHDRAW, etc.).
            if (transaction.getType() == NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW
                    && basisRemoved.signum() == 0) {
                flowSupport.applyBuy(inbound.flow(), inboundPosition);
                replayState.ledgerPointCollector().record(
                        transaction,
                        inbound.flow(),
                        inbound.index(),
                        inboundPosition.assetKey(),
                        inboundBefore,
                        inboundPosition,
                        AssetLedgerPoint.BasisEffect.ACQUIRE
                );
                continue;
            }

            CarryTransfer effectiveCarry = continuityCarryService.bridgeInboundCarry(
                    carry,
                    inbound.flow().getQuantityDelta().abs(),
                    inboundPosition.assetKey()
            );
            flowSupport.restoreToPosition(effectiveCarry, inboundPosition);
            replayState.ledgerPointCollector().record(
                    transaction,
                    inbound.flow(),
                    inbound.index(),
                    inboundPosition.assetKey(),
                    inboundBefore,
                    inboundPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }
    }

    /**
     * Cycle/9 S6: when multiple outbound legs share the same family (e.g. an Aave V3 withdraw
     * where the underlying briefly returns to the wallet then is forwarded to a gateway), pick
     * the asset whose net signed quantity over the transaction is the most negative — i.e. the
     * one that actually left the wallet. Among legs of that winning asset, pick the largest
     * absolute leg to act as the IndexedFlow handle for downstream carry. Returns {@code null}
     * if no single asset has net &lt; 0 (e.g., all outbound flows are perfectly cancelled by
     * equal inbound flows of the same asset), in which case the handler defers to generic
     * replay.
     */
    private IndexedFlow selectPrincipalOutbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> outboundFlows,
            List<IndexedFlow> inboundFlows
    ) {
        Map<String, BigDecimal> netByAsset = new LinkedHashMap<>();
        for (IndexedFlow flow : outboundFlows) {
            String identity = assetSupport.assetIdentity(transaction, flow.flow());
            if (identity == null) {
                continue;
            }
            netByAsset.merge(identity, flow.flow().getQuantityDelta(), BigDecimal::add);
        }
        for (IndexedFlow flow : inboundFlows) {
            String identity = assetSupport.assetIdentity(transaction, flow.flow());
            if (identity == null) {
                continue;
            }
            netByAsset.merge(identity, flow.flow().getQuantityDelta(), BigDecimal::add);
        }

        String dominantOutboundAsset = netByAsset.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() < 0)
                .min(Comparator.comparing(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (dominantOutboundAsset == null) {
            return null;
        }
        return outboundFlows.stream()
                .filter(flow -> Objects.equals(
                        assetSupport.assetIdentity(transaction, flow.flow()),
                        dominantOutboundAsset
                ))
                .max(Comparator.comparing(flow -> flow.flow().getQuantityDelta().abs()))
                .orElse(null);
    }

    private IndexedFlow selectPrincipalInbound(
            NormalizedTransaction transaction,
            IndexedFlow outbound,
            List<IndexedFlow> inboundFlows
    ) {
        List<IndexedFlow> distinctAssetInbound = inboundFlows.stream()
                .filter(inbound -> !Objects.equals(
                        assetSupport.assetIdentity(transaction, outbound.flow()),
                        assetSupport.assetIdentity(transaction, inbound.flow())
                ))
                .toList();
        if (distinctAssetInbound.isEmpty()) {
            // RC-E: For WRAP/UNWRAP and same-family SWAP (e.g. WAVAX→AVAX routed through a DEX
            // aggregator), both outbound and inbound flows map to the same accounting identity
            // (NATIVE:AVALANCHE).  Pairing them with CARRY_OUT/CARRY_IN on the same position
            // preserves AVCO intact — the carry exits and immediately re-enters the same bucket
            // with no market-price revaluation. This is the correct semantic for a no-op wrap.
            if (!isWrapOrUnwrap(transaction) && !isSameFamilySwap(transaction)) {
                return null;
            }
            return inboundFlows.stream()
                    .max(Comparator.comparing(f -> f.flow().getQuantityDelta().abs()))
                    .orElse(null);
        }
        // Cycle/9 S6: pick the inbound with the largest absolute quantity rather than the
        // first TRANSFER-role match. When there are multiple distinct-asset TRANSFER legs
        // (e.g. an Aave V3 withdraw that emits a tiny gateway dust refund alongside the
        // principal repayment), the dust used to win by encounter order and the principal
        // basis carry was lost.
        IndexedFlow selected = distinctAssetInbound.stream()
                .max(Comparator.comparing(
                        (IndexedFlow flow) -> flow.flow().getQuantityDelta().abs())
                        .thenComparing(flow -> flow.flow().getRole() == NormalizedLegRole.TRANSFER ? 1 : 0))
                .orElse(null);

        // Morpho Bundler anomaly guard: for VAULT_WITHDRAW where evidence is WALLET_TRANSFERS_ONLY,
        // the vault's underlying USDC may never touch the wallet (it flows directly into a Morpho Blue
        // market inside the bundler), and the only wallet-visible underlying transfer is a small
        // collateral withdrawal from a separate Morpho Blue position. In those cases the selected
        // inbound quantity is < 1% of the outbound receipt quantity (e.g. 0.66 USDC vs 1738 gtUSDCc),
        // and pairing them transfers the full vault cost basis (~$1760) to just $0.66 of USDC,
        // inflating its AVCO to ~$2666 and causing phantom realized losses of ~$933 on later REPAYs.
        // When the ratio is below 1%, skip the family-custody pair entirely: both flows fall through
        // to generic replay (DISPOSE for the receipt token, ACQUIRE for the tiny USDC) and the
        // conservation gap is attributed to the invisible bundler-internal USDC flow.
        if (selected != null
                && transaction.getType() == NormalizedTransactionType.VAULT_WITHDRAW
                && isWalletTransfersOnlyEvidence(transaction)) {
            BigDecimal outboundAbs = outbound.flow().getQuantityDelta().abs();
            BigDecimal inboundAbs = selected.flow().getQuantityDelta().abs();
            if (outboundAbs.signum() > 0) {
                BigDecimal ratio = inboundAbs.divide(outboundAbs, MC);
                if (ratio.compareTo(new BigDecimal("0.01")) < 0) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "VAULT_WITHDRAW_BUNDLER_PAIRING_SKIPPED txId={} outbound={} qty={} inbound={} qty={} ratio={}",
                                transaction.getId(),
                                outbound.flow().getAssetSymbol(),
                                outboundAbs,
                                selected.flow().getAssetSymbol(),
                                inboundAbs,
                                ratio
                        );
                    }
                    return null;
                }
            }
        }

        return selected;
    }

    private static boolean isWalletTransfersOnlyEvidence(NormalizedTransaction transaction) {
        if (transaction.getMetadata() == null) {
            return false;
        }
        return "WALLET_TRANSFERS_ONLY".equals(transaction.getMetadata().getString("evidenceCompleteness"));
    }

    private boolean isPrincipalCandidate(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (flow == null
                || flow.getRole() == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.TRANSFER) {
            return true;
        }
        if (flow.getRole() == NormalizedLegRole.BUY
                && flow.getQuantityDelta().signum() > 0
                && transaction != null
                && transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                && hasAnyTransferInboundOnSameAsset(transaction, flow)) {
            return false;
        }
        return (flow.getRole() == NormalizedLegRole.SELL && flow.getQuantityDelta().signum() < 0)
                || (flow.getRole() == NormalizedLegRole.BUY && flow.getQuantityDelta().signum() > 0);
    }

    /**
     * Returns {@code true} when the BUY flow is a suppressed duplicate that must NOT fall through to
     * generic replay. A LENDING_DEPOSIT BUY inbound is suppressed when ANY TRANSFER inbound of the
     * same asset already exists in the transaction — the TRANSFER is the canonical principal flow and
     * the BUY is an alternative encoding of the same receipt token receipt.
     *
     * <p>Two known shapes:
     * <ul>
     *   <li>Aave: TRANSFER aUSDC at human-readable scale (large) + BUY aUSDC tiny accrual (small).
     *       Original guard used "larger" comparison; the new any-TRANSFER guard also covers this.</li>
     *   <li>Silo Finance: TRANSFER soUSDC at human-readable scale (199.95) + BUY soUSDC at raw
     *       on-chain units (199835669). The BUY is 6-decimal raw, far larger numerically. Letting
     *       generic replay process the raw-unit BUY inflates the position quantity by 1e6 and doubles
     *       the cost basis, generating phantom realized losses of ~$933 on every subsequent REPAY.</li>
     * </ul>
     */
    private boolean isSuppressedAccrualBuy(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() == NormalizedLegRole.BUY
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && transaction != null
                && transaction.getType() == NormalizedTransactionType.LENDING_DEPOSIT
                && hasAnyTransferInboundOnSameAsset(transaction, flow);
    }

    /**
     * Returns {@code true} if any TRANSFER-role inbound flow for the same asset as {@code accrualFlow}
     * exists in the transaction. Used to suppress BUY duplicates in LENDING_DEPOSIT transactions
     * (see {@link #isSuppressedAccrualBuy}).
     */
    private boolean hasAnyTransferInboundOnSameAsset(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow accrualFlow
    ) {
        if (transaction.getFlows() == null) {
            return false;
        }
        String accrualAsset = assetSupport.assetIdentity(transaction, accrualFlow);
        for (NormalizedTransaction.Flow candidate : transaction.getFlows()) {
            if (candidate == null
                    || candidate.getRole() != NormalizedLegRole.TRANSFER
                    || candidate.getQuantityDelta() == null
                    || candidate.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!Objects.equals(accrualAsset, assetSupport.assetIdentity(transaction, candidate))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    /**
     * P0-D: Returns true for WRAP/UNWRAP transactions that require strict AVCO preservation
     * from source to destination bucket (ETH→WETH or WETH→ETH).
     */
    private static boolean isWrapOrUnwrap(NormalizedTransaction transaction) {
        return transaction != null
                && (transaction.getType() == NormalizedTransactionType.WRAP
                        || transaction.getType() == NormalizedTransactionType.UNWRAP);
    }

    /**
     * RC-E: Detects a SWAP classified transaction that is economically a native-alias wrap/unwrap
     * (e.g. WAVAX→AVAX routed through Paraswap/Velora). Such transactions must NOT be processed as
     * DISPOSE+ACQUIRE because that changes AVCO when market price differs from cost basis. Instead
     * they must be treated as CARRY_OUT/CARRY_IN to preserve basis intact.
     *
     * <p>Detection criterion: transaction type is SWAP and all principal (non-zero-qty) flows
     * belong to exactly one asset family (e.g. FAMILY:AVAX). If flows span multiple families (e.g.
     * ETH→USDC), the swap is a genuine cross-family exchange and must use normal DISPOSE+ACQUIRE.
     *
     * <p>Quantity-ratio guard: a true wrap/unwrap exchanges assets at approximately a 1:1 ratio
     * (e.g. 1 WETH → 1 ETH). If total inbound quantity is less than 10% of total outbound quantity
     * (or vice versa), the captured flows represent only a fragment of the actual trade — the
     * primary output was directed elsewhere (e.g. a WETH→USDC swap routed via an internal ETH
     * unwrap, where only the WETH sold and ETH dust are captured). In such cases the same-family
     * detection is a false positive and normal DISPOSE+ACQUIRE must be used to prevent the full
     * outbound basis from being allocated to a trivially small inbound quantity, causing an absurd
     * AVCO spike (e.g. LINEA WETH 0.01153 → ETH 6.77e-7 = 17,000× mismatch → ETH AVCO $208k).
     */
    private boolean isSameFamilySwap(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getType() != NormalizedTransactionType.SWAP) {
            return false;
        }
        Set<String> canonicalIdentities = new HashSet<>();
        BigDecimal totalOutbound = BigDecimal.ZERO;
        BigDecimal totalInbound = BigDecimal.ZERO;
        boolean hasOutbound = false;
        boolean hasInbound = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                // FEE flows do not participate in the identity check or quantity ratio guard.
                continue;
            }
            String identity = AccountingAssetClassificationSupport.canonicalTokenIdentity(
                    flow.getAssetSymbol(),
                    flow.getAssetContract()
            );
            if (identity == null || identity.isBlank()) {
                return false;
            }
            canonicalIdentities.add(identity);
            if (flow.getQuantityDelta().signum() < 0) {
                hasOutbound = true;
                totalOutbound = totalOutbound.add(flow.getQuantityDelta().abs());
            } else {
                hasInbound = true;
                totalInbound = totalInbound.add(flow.getQuantityDelta());
            }
        }
        if (canonicalIdentities.size() != 1 || !hasOutbound || !hasInbound) {
            return false;
        }
        // Quantity-ratio guard: for a true wrap/unwrap the inbound and outbound quantities must be
        // within one order of magnitude of each other. Ratio < 0.1 indicates a false positive where
        // only a dust fragment of the trade was captured in this direction.
        if (totalOutbound.signum() > 0 && totalInbound.signum() > 0) {
            BigDecimal ratio = totalInbound.divide(totalOutbound, MathContext.DECIMAL128);
            BigDecimal threshold = new BigDecimal("0.10");
            if (ratio.compareTo(threshold) < 0 || ratio.compareTo(BigDecimal.ONE.divide(threshold, MathContext.DECIMAL128)) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isSimpleFamilyEquivalentCustodyType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case PROTOCOL_CUSTODY_DEPOSIT,
                    PROTOCOL_CUSTODY_WITHDRAW,
                    LENDING_DEPOSIT,
                    LENDING_WITHDRAW,
                    EARN_FLEXIBLE_SAVING,
                    WRAP,
                    UNWRAP,
                    VAULT_DEPOSIT,
                    VAULT_WITHDRAW -> true;
            default -> false;
        };
    }
}
