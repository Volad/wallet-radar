package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.LpReceiptSymbolSupport;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;
import com.walletradar.application.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.application.costbasis.application.replay.model.AsyncLifecycleBucket;
import com.walletradar.application.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PositionScopedLpExitReplayHandler {

    private static final Logger log = LoggerFactory.getLogger(PositionScopedLpExitReplayHandler.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplaySettlementAllocator settlementAllocator;
    private final LpReceiptBasisPoolService lpReceiptBasisPoolService;
    private final ReplayPendingTransferKeyFactory pendingTransferKeyFactory;

    public PositionScopedLpExitReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplaySettlementAllocator settlementAllocator,
            LpReceiptBasisPoolService lpReceiptBasisPoolService,
            ReplayPendingTransferKeyFactory pendingTransferKeyFactory
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.settlementAllocator = settlementAllocator;
        this.lpReceiptBasisPoolService = lpReceiptBasisPoolService;
        this.pendingTransferKeyFactory = pendingTransferKeyFactory;
    }

    public boolean isPositionScopedLpExit(NormalizedTransaction transaction) {
        return transaction != null
                && isLpExitType(transaction.getType())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    public boolean shouldIgnoreLpReceiptMarker(NormalizedTransaction transaction, NormalizedTransaction.Flow flow) {
        if (transaction == null || flow == null || flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        if (transaction.getType() == NormalizedTransactionType.LP_ENTRY) {
            // Outbound-only LP_ENTRY (Pancake/Aerodrome receipt-pool path): ignore synthetic inbound markers.
            // Curve/Balancer same-tx receipt legs must flow through composite lp: bucket restore.
            return LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(transaction)
                    && flow.getQuantityDelta().signum() > 0;
        }
        if (!isLpExitType(transaction.getType()) || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        // ADR-081 (C1): the explicitly flagged LP-receipt burn leg (Meteora DAMM MLP — a fungible
        // receipt whose symbol is confusable across pools and never matches the composite receipt
        // identity below) is drained by drainMaterializedReceiptMarker as a basis-neutral
        // REALLOCATE_OUT. Skip it in the main settlement loop so it is not also booked as an UNKNOWN
        // disposal (which would leave a phantom net-nonzero MLP point / double record).
        if (Boolean.TRUE.equals(flow.getLpReceipt())) {
            return true;
        }
        // Only skip the burned LP receipt leg on true exits. Mint-shaped LP_EXIT rows carry
        // negative underlying deposits that must reach the composite lp: bucket first.
        String receiptIdentity = pendingTransferKeyFactory.lpCompositeReceiptIdentity(transaction);
        if (receiptIdentity == null) {
            return false;
        }
        // LP-RECEIPT tokens have continuityIdentity == "FAMILY:LP-RECEIPT" (generic family alias).
        // lpCompositeReceiptIdentity now returns the specific assetIdentity for LP-RECEIPT burns
        // (e.g. "SYMBOL:LP-RECEIPT:AVALANCHE:BALANCERV3:0x..."), so compare with assetIdentity
        // on the flow side to ensure the identities are symmetrically specific.
        String flowIdentity = AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())
                ? assetSupport.assetIdentity(transaction, flow)
                : assetSupport.continuityIdentity(transaction, flow);
        return receiptIdentity.equals(flowIdentity);
    }

    public void apply(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        SettlementOutcome outcome = applySettlement(transaction, replayState);
        if (shouldDrainMaterializedReceiptMarker(transaction, replayState, outcome)) {
            drainMaterializedReceiptMarker(transaction, replayState);
        }
    }

    private record SettlementOutcome(boolean touchedLpReceiptPrincipal) {
    }

    private SettlementOutcome applySettlement(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        // R6a partial-burn proportionality: a PARTIAL Balancer V3 BPT burn must release only the
        // per-asset receipt-pool basis PROPORTIONAL to the BPT actually burned in this tx — mirroring
        // the proportional LP-RECEIPT marker decrement (reducePartialReceiptMarker). Historically the
        // per-asset pools drained by returned-underlying quantity, which over-drains a rebalanced
        // stable-pool exit (returned USDT/USDC can exceed their own pool qty, sweeping the sibling
        // pool leftover too) and empties the pool while a portion of the BPT is still staked. The
        // later burn (after the residual BPT is unstaked and re-burned) then finds no basis left and
        // self-assigns UNKNOWN at face, fabricating a gain. Scale the correlation's per-asset pools
        // DOWN to the burned fraction before settlement (stashing the residual), let settlement drain
        // the active fraction, then restore the stashed residual so the later burn draws it as
        // REALLOCATE_IN. Full closes (burnQty == held) are unaffected (residualFraction == null).
        BigDecimal residualFraction = partialBurnResidualFraction(transaction, replayState);
        if (residualFraction == null) {
            return applySettlementInner(transaction, replayState);
        }
        Map<LpReceiptBasisPoolKey, StashedPoolResidual> stashedResidual =
                reserveResidualPoolBasisForPartialBurn(transaction, replayState, residualFraction);
        try {
            return applySettlementInner(transaction, replayState);
        } finally {
            restoreReservedResidualPoolBasis(replayState, stashedResidual);
        }
    }

    private SettlementOutcome applySettlementInner(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());
        List<IndexedFlow> residualPrincipalInflows = new ArrayList<>();
        List<IndexedFlow> deferredCrossAssetInflows = new ArrayList<>();
        Set<String> eligibleIdentities = bucket.knownAssetIdentities();
        Set<String> touchedEligibleIdentities = new LinkedHashSet<>();
        boolean touchedEligiblePrincipal = false;
        boolean touchedLpReceiptPrincipal = false;

        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (shouldIgnoreLpReceiptMarker(transaction, flow)) {
                continue;
            }

            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot before = flowSupport.snapshot(position);

            if (flow.getRole() == NormalizedLegRole.FEE) {
                flowSupport.applyFee(flow, position);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.LP_FEE_INCOME) {
                // R1/D4: fee income at LP exit — zero-cost acquisition (income stays implicit in AVCO).
                // Net lane: $0 cost (ZeroCostAcquisitionSupport recognises LP_FEE_CLAIM as zero-net).
                // Tax lane: FMV at the exit block/timestamp. D4 — the engine books the acquisition
                // cost that is PASSED here (it does NOT re-read the flow price), so compute FMV =
                // qty × resolved unit price explicitly; unresolved price falls back to $0 (no
                // fabrication). This captures the ~$24 ETH + ~$24 USDC of previously-unpriced fee
                // income exactly once — the split fee legs are the sole fee-income representation
                // (principal return-of-capital flows through the REALLOCATE_IN legs), so no double count.
                BigDecimal feeUnitPrice = assetSupport.replayUnitPriceUsd(transaction, flow);
                BigDecimal feeTaxCostUsd = feeUnitPrice != null
                        ? flow.getQuantityDelta().abs().multiply(feeUnitPrice, MC)
                        : BigDecimal.ZERO;
                flowSupport.applyBuyWithAcquisitionCost(
                        flow,
                        position,
                        feeTaxCostUsd,
                        NormalizedTransactionType.LP_FEE_CLAIM
                );
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.ACQUIRE
                );
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                flowSupport.applyUnknownTransfer(flow, position);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
                continue;
            }
            if (flow.getQuantityDelta().signum() > 0) {
                if (restoreInboundFromLpReceiptPool(
                        transaction,
                        indexedFlow,
                        position,
                        before,
                        replayState,
                        residualPrincipalInflows
                )) {
                    touchedLpReceiptPrincipal = true;
                    // R2: mark this LP-pool-restored asset as a touched eligible identity so that
                    // shouldIsolateNonPrincipalInflows can isolate sideflows (e.g. USDT reward)
                    // that co-arrive in a dual-asset exit but do not belong to the LP principal set.
                    String lpRestoredId = assetSupport.continuityIdentity(transaction, flow);
                    if (lpRestoredId != null) {
                        touchedEligibleIdentities.add(lpRestoredId);
                        touchedEligiblePrincipal = true;
                    }
                    continue;
                }
            }
            if (flow.getQuantityDelta().signum() < 0) {
                flowSupport.applyUnknownTransfer(flow, position);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.UNKNOWN
                );
                continue;
            }

            String bucketIdentity = assetSupport.continuityIdentity(transaction, flow);
            if (!eligibleIdentities.contains(bucketIdentity)) {
                deferredCrossAssetInflows.add(indexedFlow);
                continue;
            }

            touchedEligiblePrincipal = true;
            touchedEligibleIdentities.add(bucketIdentity);
            CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(bucketIdentity, flow.getQuantityDelta().abs(), position.assetKey());
            if (sameAssetCarry != null) {
                // U-3: same-asset carry — cap a USD-stablecoin underlying at the $1 peg.
                BigDecimal sameAssetCovered = sameAssetCarry.coveredQuantity() != null
                        ? sameAssetCarry.coveredQuantity()
                        : sameAssetCarry.quantity();
                BigDecimal sameAssetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                        position.assetKey(), sameAssetCovered, sameAssetCarry.costBasisUsd());
                // BB-LP-CMETH-1 (T4b): cap net lane symmetrically for same-asset stablecoin carry.
                BigDecimal sameAssetNetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                        position.assetKey(), sameAssetCovered, sameAssetCarry.netCostBasisUsd());
                BigDecimal sameAssetAvco = sameAssetBasis.signum() > 0
                        && sameAssetCovered != null && sameAssetCovered.signum() > 0
                        ? sameAssetBasis.divide(sameAssetCovered, MC)
                        : sameAssetCarry.avco();
                flowSupport.restoreToPosition(
                        sameAssetCarry.quantity(),
                        position,
                        sameAssetBasis,
                        sameAssetNetBasis,
                        sameAssetCarry.uncoveredQuantity(),
                        sameAssetAvco
                );
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(sameAssetCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    residualPrincipalInflows.add(new IndexedFlow(
                            indexedFlow.index(),
                            flowSupport.copyFlowWithQuantity(flow, residualQuantity)
                    ));
                }
                continue;
            }

            residualPrincipalInflows.add(indexedFlow);
        }

        // D1 (R2 Option-B2): on a closing dual-token / rebalancing exit, carry any un-drained
        // sibling-pool basis onto the residual principal (return-of-capital) instead of booking it
        // as a market-priced ACQUIRE (income). Must run after the whole flow loop so every asset has
        // already drained its own pool.
        if (touchedLpReceiptPrincipal && !residualPrincipalInflows.isEmpty()) {
            residualPrincipalInflows = carryResidualPrincipalFromRemainingPools(
                    transaction, replayState, residualPrincipalInflows);
        }

        if (residualPrincipalInflows.isEmpty() && deferredCrossAssetInflows.isEmpty()) {
            if (bucket.isEmpty()) {
                replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            }
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }

        if (shouldIsolateNonPrincipalInflows(
                bucket,
                touchedEligiblePrincipal || touchedLpReceiptPrincipal,
                touchedEligibleIdentities
        )) {
            recordSideflowLpExitInflows(transaction, replayState, residualPrincipalInflows);
            recordSideflowLpExitInflows(transaction, replayState, deferredCrossAssetInflows);
            if (bucket.isEmpty()) {
                replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            }
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }

        List<IndexedFlow> allocatableInflows = residualPrincipalInflows.isEmpty()
                ? deferredCrossAssetInflows
                : residualPrincipalInflows;
        List<IndexedFlow> unknownOnlyInflows = residualPrincipalInflows.isEmpty()
                ? List.of()
                : deferredCrossAssetInflows;

        if (!touchedEligiblePrincipal
                && !touchedLpReceiptPrincipal
                && residualPrincipalInflows.isEmpty()
                && deferredCrossAssetInflows.size() == 1) {
            recordUnknownLpExitInflows(transaction, replayState, deferredCrossAssetInflows);
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0) {
            recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }

        List<NormalizedTransaction.Flow> residualFlows = allocatableInflows.stream()
                .map(IndexedFlow::flow)
                .toList();
        if (assetSupport.allSameAsset(residualFlows, transaction)) {
            settlementAllocator.allocateIndexedSettlementByQuantity(
                    transaction,
                    allocatableInflows,
                    replayState.positions(),
                    remainingCostBasis,
                    replayState.ledgerPointCollector()
            );
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }
        if (assetSupport.allHaveKnownReplayPrices(transaction, allocatableInflows)) {
            settlementAllocator.allocateIndexedSettlementByReplayKnownValue(
                    transaction,
                    allocatableInflows,
                    replayState.positions(),
                    remainingCostBasis,
                    replayState.ledgerPointCollector()
            );
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }
        if (bucket.remainingUncoveredQuantity().signum() > 0) {
            recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return new SettlementOutcome(touchedLpReceiptPrincipal);
        }

        recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
        recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
        bucket.clearAll();
        replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
        return new SettlementOutcome(touchedLpReceiptPrincipal);
    }

    private boolean shouldDrainMaterializedReceiptMarker(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            SettlementOutcome outcome
    ) {
        if (transaction == null || transaction.getCorrelationId() == null) {
            return false;
        }
        // RC-S-LP-CLOSE: a terminal exit (position NFT/PDA closed on-chain) drains every residual
        // per-asset basis pool for the position. Solana concentrated-liquidity positions are
        // NFT-based with no fungible LP-RECEIPT burn leg, so hasPrincipalCloseEvidence() cannot see
        // the close; the normalizer instead promotes the terminal remove to LP_EXIT_FINAL. Draining
        // here zeroes any per-asset residual left by a changed asset ratio (impermanent loss /
        // rebalancing) and writes it off as realized LP PnL — no phantom "still held" quantities.
        if (transaction.getType() == NormalizedTransactionType.LP_EXIT_FINAL) {
            return true;
        }
        if (hasPrincipalCloseEvidence(transaction)) {
            return true;
        }
        if (outcome != null
                && outcome.touchedLpReceiptPrincipal()
                && allLpReceiptPoolsEmpty(transaction.getCorrelationId(), replayState)) {
            return true;
        }
        return replayState.lpReceiptLifecycleClosed(transaction.getCorrelationId());
    }

    private boolean allLpReceiptPoolsEmpty(String correlationId, ReplayExecutionState replayState) {
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null || correlationId == null) {
            return false;
        }
        boolean sawPool = false;
        for (var entry : poolContext.pools().entrySet()) {
            if (!correlationId.equals(entry.getKey().lpCorrelationId())) {
                continue;
            }
            sawPool = true;
            LpReceiptBasisPool pool = entry.getValue();
            if (pool != null && pool.getQtyHeld() != null && pool.getQtyHeld().signum() > 0) {
                return false;
            }
        }
        return sawPool;
    }

    private void drainMaterializedReceiptMarker(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || transaction.getCorrelationId() == null) {
            return;
        }
        replayState.recordLpReceiptPrincipalExitEvent(transaction.getCorrelationId());

        // Locate the explicit LP-RECEIPT burn flow. When synthesizeReceiptFromOutbound stored the
        // position via assetKey(transaction, explicitReceiptFlow) (Balancer V3 BPT style), that key
        // has assetContract = "SYMBOL:LP-RECEIPT:..." and differs from the lpReceiptPositionKey
        // (assetContract=null). We resolve the active receipt position by checking both key forms
        // so that Balancer V3 BPT pools and NFT-style pools (Uniswap V3, Aerodrome) are both covered.
        ResolvedReceiptMarker resolved = resolveActiveReceiptMarker(transaction, replayState);
        if (resolved == null) {
            return;
        }
        IndexedFlow markerFlow = resolved.markerFlow();
        PositionState receiptPosition = resolved.position();
        if (receiptPosition.quantity().signum() <= 0) {
            drainAllLpReceiptPoolsForCorrelation(transaction, replayState);
            return;
        }

        // R6a: PARTIAL burn of a fungible Balancer V3 BPT receipt. When the burned quantity is
        // strictly less than the held receipt quantity, the position is NOT closed — the remainder
        // stays live (e.g. re-staked into Aura, then burned in a later tx). Reduce the receipt marker
        // proportionally and DO NOT drain the per-asset pools: applySettlement already scaled the
        // pools to the burned fraction and restored the still-held residual, so the pools now hold
        // exactly the un-burned portion's basis, which must survive for the next burn. Only fungible
        // BPT receipts (real token quantities) qualify; NFT-style receipts are qty=1 and always fall
        // through to the full close below (unchanged behaviour).
        if (markerFlow != null
                && isBalancerV3ReceiptSymbol(markerFlow.flow().getAssetSymbol())
                && isPartialBurn(markerFlow.flow().getQuantityDelta().abs(), receiptPosition.quantity())) {
            reducePartialReceiptMarker(transaction, markerFlow, receiptPosition, replayState);
            return;
        }

        PositionSnapshot before = flowSupport.snapshot(receiptPosition);
        receiptPosition.setQuantity(BigDecimal.ZERO);
        receiptPosition.setTotalCostBasisUsd(BigDecimal.ZERO);
        receiptPosition.setNetTotalCostBasisUsd(BigDecimal.ZERO);
        receiptPosition.setUncoveredQuantity(BigDecimal.ZERO);
        receiptPosition.setPerWalletAvco(null);
        receiptPosition.setPerWalletNetAvco(null);
        if (markerFlow != null) {
            replayState.ledgerPointCollector().record(
                    transaction,
                    markerFlow.flow(),
                    markerFlow.index(),
                    receiptPosition.assetKey(),
                    before,
                    receiptPosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
        }
        drainAllLpReceiptPoolsForCorrelation(transaction, replayState);
    }

    /** Resolved active LP-RECEIPT marker position for an exit (shared by drain + partial-burn scaling). */
    private record ResolvedReceiptMarker(AssetKey key, PositionState position, IndexedFlow markerFlow) {
    }

    /**
     * Locates the outbound LP-RECEIPT burn/marker flow and the position that currently holds the
     * receipt quantity. Balancer V3 BPT pools store the position under an assetKey derived from the
     * explicit burn flow (assetContract = "SYMBOL:LP-RECEIPT:..."), while NFT-style pools (Uniswap V3,
     * Aerodrome) key it off the correlationId. Both key forms are probed; the fallback returns the
     * correlationId-derived position even when its quantity is ≤ 0 so downstream pool drains still run.
     */
    private ResolvedReceiptMarker resolveActiveReceiptMarker(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        IndexedFlow markerFlow = null;
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow f = indexedFlow.flow();
            if (f == null
                    || f.getRole() != NormalizedLegRole.TRANSFER
                    || f.getQuantityDelta() == null
                    || f.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (AccountingAssetFamilySupport.isLpReceiptSymbol(f.getAssetSymbol())
                    || Boolean.TRUE.equals(f.getLpReceipt())) {
                markerFlow = indexedFlow;
                break;
            }
        }

        // Primary key: correlationId-derived (NFT-style / legacy path).
        AssetKey corrKey = assetSupport.lpReceiptPositionKey(transaction, transaction.getCorrelationId());
        // Flow key: assetKey from the explicit burn flow (Balancer V3 BPT style).
        AssetKey flowKey = markerFlow != null ? assetSupport.assetKey(transaction, markerFlow.flow()) : null;

        // Pick whichever position actually holds the receipt quantity.
        AssetKey receiptKey = null;
        PositionState receiptPosition = null;
        if (corrKey != null) {
            PositionState cp = replayState.position(corrKey);
            if (cp.quantity().signum() > 0) {
                receiptKey = corrKey;
                receiptPosition = cp;
            }
        }
        if (receiptKey == null && flowKey != null && !flowKey.equals(corrKey)) {
            PositionState fp = replayState.position(flowKey);
            if (fp.quantity().signum() > 0) {
                receiptKey = flowKey;
                receiptPosition = fp;
            }
        }
        // Fall back to corrKey even if quantity ≤ 0 (so pool drain still runs).
        if (receiptKey == null) {
            receiptKey = corrKey != null ? corrKey : flowKey;
            receiptPosition = receiptKey != null ? replayState.position(receiptKey) : null;
        }
        if (receiptKey == null || receiptPosition == null) {
            return null;
        }
        return new ResolvedReceiptMarker(receiptKey, receiptPosition, markerFlow);
    }

    /**
     * R6a: residual (still-held) fraction {@code 1 − burnFraction} when this exit is a PARTIAL burn of
     * a fungible Balancer V3 BPT receipt (burned qty strictly less than the held receipt qty), else
     * {@code null}. Used to scale the per-asset receipt pools down to the burned fraction so a partial
     * burn returns only its proportional basis and leaves the still-staked portion for a later burn.
     */
    private BigDecimal partialBurnResidualFraction(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction == null || transaction.getCorrelationId() == null) {
            return null;
        }
        ResolvedReceiptMarker resolved = resolveActiveReceiptMarker(transaction, replayState);
        if (resolved == null || resolved.markerFlow() == null || resolved.position() == null) {
            return null;
        }
        NormalizedTransaction.Flow markerFlow = resolved.markerFlow().flow();
        if (!isBalancerV3ReceiptSymbol(markerFlow.getAssetSymbol())) {
            return null;
        }
        BigDecimal heldQty = resolved.position().quantity();
        if (heldQty == null || heldQty.signum() <= 0) {
            return null;
        }
        BigDecimal burnQty = markerFlow.getQuantityDelta().abs();
        if (!isPartialBurn(burnQty, heldQty)) {
            return null;
        }
        BigDecimal residualFraction = BigDecimal.ONE.subtract(burnQty.divide(heldQty, MC), MC);
        return residualFraction.signum() > 0 ? residualFraction : null;
    }

    /** Immutable residual carved out of a receipt pool for the duration of a partial-burn settlement. */
    private record StashedPoolResidual(
            BigDecimal qty,
            BigDecimal basis,
            BigDecimal netBasis,
            BigDecimal uncovered
    ) {
    }

    /**
     * R6a: scales every per-asset receipt pool of the exit's correlation DOWN to the burned fraction
     * ({@code 1 − residualFraction}), returning the carved-out residual so it can be restored after
     * settlement. Because settlement drains the (scaled) active portion, the correlation's total
     * REALLOCATE_IN basis for a partial burn equals {@code burnFraction × combinedBasis}, matching the
     * proportional LP-RECEIPT marker decrement. All lanes (qty, tax, net, uncovered) scale by the same
     * fraction, so AVCO and the net ≤ tax invariant are preserved.
     */
    private Map<LpReceiptBasisPoolKey, StashedPoolResidual> reserveResidualPoolBasisForPartialBurn(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            BigDecimal residualFraction
    ) {
        Map<LpReceiptBasisPoolKey, StashedPoolResidual> stashed = new LinkedHashMap<>();
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        String corrId = transaction == null ? null : transaction.getCorrelationId();
        if (poolContext == null || corrId == null
                || residualFraction == null || residualFraction.signum() <= 0) {
            return stashed;
        }
        for (var entry : poolContext.pools().entrySet()) {
            LpReceiptBasisPoolKey key = entry.getKey();
            if (!corrId.equals(key.lpCorrelationId())) {
                continue;
            }
            LpReceiptBasisPool pool = entry.getValue();
            if (pool == null) {
                continue;
            }
            BigDecimal qty = nullToZero(pool.getQtyHeld());
            BigDecimal basis = nullToZero(pool.getBasisHeldUsd());
            BigDecimal net = pool.getNetBasisHeldUsd() != null ? pool.getNetBasisHeldUsd() : basis;
            BigDecimal uncovered = nullToZero(pool.getUncoveredQtyHeld());
            if (qty.signum() <= 0 && basis.signum() <= 0 && net.signum() <= 0) {
                continue;
            }
            BigDecimal resQty = qty.multiply(residualFraction, MC);
            BigDecimal resBasis = basis.multiply(residualFraction, MC);
            BigDecimal resNet = net.multiply(residualFraction, MC);
            BigDecimal resUncovered = uncovered.multiply(residualFraction, MC);
            pool.setQtyHeld(qty.subtract(resQty, MC));
            pool.setBasisHeldUsd(basis.subtract(resBasis, MC));
            pool.setNetBasisHeldUsd(net.subtract(resNet, MC));
            pool.setUncoveredQtyHeld(uncovered.subtract(resUncovered, MC));
            recomputeReceiptPoolAvco(pool);
            poolContext.dirtyKeys().add(key);
            stashed.put(key, new StashedPoolResidual(resQty, resBasis, resNet, resUncovered));
        }
        return stashed;
    }

    /**
     * R6a: adds the carved-out residual back onto each receipt pool after a partial-burn settlement has
     * drained the active fraction. The pools then hold exactly the still-held (e.g. re-staked) portion's
     * basis, which a later burn draws as REALLOCATE_IN instead of self-assigning UNKNOWN at face.
     */
    private void restoreReservedResidualPoolBasis(
            ReplayExecutionState replayState,
            Map<LpReceiptBasisPoolKey, StashedPoolResidual> stashed
    ) {
        if (stashed == null || stashed.isEmpty()) {
            return;
        }
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null) {
            return;
        }
        for (var e : stashed.entrySet()) {
            LpReceiptBasisPool pool = poolContext.pools().get(e.getKey());
            if (pool == null) {
                continue;
            }
            StashedPoolResidual r = e.getValue();
            BigDecimal net = pool.getNetBasisHeldUsd() != null
                    ? pool.getNetBasisHeldUsd()
                    : nullToZero(pool.getBasisHeldUsd());
            pool.setQtyHeld(nullToZero(pool.getQtyHeld()).add(r.qty(), MC));
            pool.setBasisHeldUsd(nullToZero(pool.getBasisHeldUsd()).add(r.basis(), MC));
            pool.setNetBasisHeldUsd(net.add(r.netBasis(), MC));
            pool.setUncoveredQtyHeld(nullToZero(pool.getUncoveredQtyHeld()).add(r.uncovered(), MC));
            recomputeReceiptPoolAvco(pool);
            poolContext.dirtyKeys().add(e.getKey());
        }
    }

    private void recomputeReceiptPoolAvco(LpReceiptBasisPool pool) {
        BigDecimal qty = nullToZero(pool.getQtyHeld());
        BigDecimal basis = nullToZero(pool.getBasisHeldUsd());
        pool.setAvcoUsd(qty.signum() > 0 && basis.signum() > 0 ? basis.divide(qty, MC) : null);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** R6a: {@code true} when {@code symbol} is a Balancer V3 fungible BPT LP-RECEIPT symbol. */
    private boolean isBalancerV3ReceiptSymbol(String symbol) {
        return symbol != null
                && AccountingAssetFamilySupport.isLpReceiptSymbol(symbol)
                && symbol.toUpperCase(java.util.Locale.ROOT).contains(":BALANCERV3:");
    }

    /**
     * R6a: {@code true} when {@code burnQty} is strictly (beyond a tiny relative rounding tolerance)
     * less than {@code positionQty} — i.e. the receipt burn does not close the whole position.
     */
    private boolean isPartialBurn(BigDecimal burnQty, BigDecimal positionQty) {
        if (burnQty == null || positionQty == null || positionQty.signum() <= 0) {
            return false;
        }
        BigDecimal remainder = positionQty.subtract(burnQty, MC);
        // Tolerance guards against rounding drift accumulated through CARRY_IN/OUT: a burn within
        // 1e-9 (relative) of the held quantity is treated as a full close, not a partial.
        BigDecimal tolerance = positionQty.multiply(new BigDecimal("1E-9"), MC);
        return remainder.compareTo(tolerance) > 0;
    }

    /**
     * R6a: reduces a fungible Balancer V3 receipt marker position by the burned quantity on a
     * partial exit (proportional basis reduction, AVCO unchanged), records the burn as a
     * basis-neutral {@code REALLOCATE_OUT}, and leaves the per-asset pools untouched so the residual
     * basis survives for the next burn.
     */
    private void reducePartialReceiptMarker(
            NormalizedTransaction transaction,
            IndexedFlow markerFlow,
            PositionState receiptPosition,
            ReplayExecutionState replayState
    ) {
        BigDecimal positionQty = receiptPosition.quantity();
        BigDecimal burnQty = markerFlow.flow().getQuantityDelta().abs();
        BigDecimal remainingFraction = BigDecimal.ONE.subtract(burnQty.divide(positionQty, MC), MC);
        if (remainingFraction.signum() < 0) {
            remainingFraction = BigDecimal.ZERO;
        }
        PositionSnapshot before = flowSupport.snapshot(receiptPosition);
        BigDecimal tax = receiptPosition.totalCostBasisUsd() == null
                ? BigDecimal.ZERO
                : receiptPosition.totalCostBasisUsd();
        BigDecimal net = receiptPosition.netTotalCostBasisUsd() == null
                ? BigDecimal.ZERO
                : receiptPosition.netTotalCostBasisUsd();
        BigDecimal uncovered = receiptPosition.uncoveredQuantity() == null
                ? BigDecimal.ZERO
                : receiptPosition.uncoveredQuantity();
        receiptPosition.setQuantity(positionQty.subtract(burnQty, MC));
        receiptPosition.setTotalCostBasisUsd(tax.multiply(remainingFraction, MC));
        receiptPosition.setNetTotalCostBasisUsd(net.multiply(remainingFraction, MC));
        receiptPosition.setUncoveredQuantity(uncovered.multiply(remainingFraction, MC));
        // Per-unit AVCO is unchanged by a proportional reduction; leave perWalletAvco as-is.
        replayState.ledgerPointCollector().record(
                transaction,
                markerFlow.flow(),
                markerFlow.index(),
                receiptPosition.assetKey(),
                before,
                receiptPosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
        );
    }

    private void drainAllLpReceiptPoolsForCorrelation(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        String correlationId = transaction == null ? null : transaction.getCorrelationId();
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null || correlationId == null) {
            return;
        }
        // D2 (R3 net-lane carry): sum any basis still held across the correlation's pools BEFORE
        // zeroing. On a full exit this residual would otherwise be silently destroyed (the audit
        // measured ≈ −$1,856 net destroyed). Carry it (tax + net, net ≤ tax) onto the exit asset so
        // the net lane is conserved, THEN zero — ordering matters. D1 already zeroes the pools when
        // it re-allocates a rebalancing residual, so this path only fires for leftover that the
        // per-flow settlement did not carry (e.g. peg-cap surplus not fully absorbed).
        BigDecimal leftoverTax = BigDecimal.ZERO;
        BigDecimal leftoverNet = BigDecimal.ZERO;
        List<LpReceiptBasisPoolKey> correlationKeys = new ArrayList<>();
        for (var entry : poolContext.pools().entrySet()) {
            if (!correlationId.equals(entry.getKey().lpCorrelationId())) {
                continue;
            }
            LpReceiptBasisPool pool = entry.getValue();
            if (pool == null) {
                continue;
            }
            correlationKeys.add(entry.getKey());
            BigDecimal tax = pool.getBasisHeldUsd() == null ? BigDecimal.ZERO : pool.getBasisHeldUsd();
            BigDecimal net = pool.getNetBasisHeldUsd() == null ? tax : pool.getNetBasisHeldUsd();
            leftoverTax = leftoverTax.add(tax, MC);
            leftoverNet = leftoverNet.add(net, MC);
        }
        if (leftoverTax.signum() > 0 || leftoverNet.signum() > 0) {
            carryLeftoverPoolBasisOntoExitAsset(transaction, replayState, leftoverTax, leftoverNet);
        }
        for (LpReceiptBasisPoolKey key : correlationKeys) {
            LpReceiptBasisPool pool = poolContext.pools().get(key);
            if (pool == null) {
                continue;
            }
            pool.setQtyHeld(BigDecimal.ZERO);
            pool.setBasisHeldUsd(BigDecimal.ZERO);
            pool.setNetBasisHeldUsd(BigDecimal.ZERO);
            pool.setUncoveredQtyHeld(BigDecimal.ZERO);
            poolContext.dirtyKeys().add(key);
        }
    }

    /**
     * D2 (R3 net-lane carry): attaches leftover pool basis (tax + net) to the exit asset as a
     * {@code REALLOCATE_IN} basis adjustment (no new quantity) so the net lane is conserved rather
     * than destroyed at pool drain. The carry target is a returned principal leg in the exit
     * transaction — a volatile leg is preferred (it can absorb above-face basis without breaking the
     * stablecoin peg), falling back to the largest returned principal. When the exit returns no
     * principal to carry onto (fee-only / receipt-only), the leftover cannot be conserved and is
     * dropped with a warning (never fabricated onto an unrelated asset).
     */
    private void carryLeftoverPoolBasisOntoExitAsset(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            BigDecimal leftoverTax,
            BigDecimal leftoverNet
    ) {
        IndexedFlow target = selectLeftoverCarryTarget(transaction);
        if (target == null) {
            log.warn("D2 net-carry: no returned principal to carry leftover LP pool basis onto; "
                    + "corrId={} tax={} net={}", transaction.getCorrelationId(), leftoverTax, leftoverNet);
            return;
        }
        BigDecimal net = leftoverNet.min(leftoverTax).max(BigDecimal.ZERO);
        NormalizedTransaction.Flow flow = target.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        // Basis-only adjustment: add carried basis without minting quantity.
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(leftoverTax, MC));
        position.setNetTotalCostBasisUsd(position.netTotalCostBasisUsd().add(net, MC));
        flowSupport.recomputePerWalletAvco(position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                target.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    /**
     * D2: picks the returned principal leg to receive leftover pool basis — the largest returned
     * volatile (non-stablecoin) principal, else the largest returned stablecoin principal.
     */
    private IndexedFlow selectLeftoverCarryTarget(NormalizedTransaction transaction) {
        IndexedFlow volatileTarget = null;
        BigDecimal volatileWeight = BigDecimal.ZERO;
        IndexedFlow stableTarget = null;
        BigDecimal stableWeight = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0
                    || Boolean.TRUE.equals(flow.getLpReceipt())
                    || AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())) {
                continue;
            }
            BigDecimal weight = residualWeight(transaction, indexedFlow);
            if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
                if (stableTarget == null || weight.compareTo(stableWeight) > 0) {
                    stableTarget = indexedFlow;
                    stableWeight = weight;
                }
            } else if (volatileTarget == null || weight.compareTo(volatileWeight) > 0) {
                volatileTarget = indexedFlow;
                volatileWeight = weight;
            }
        }
        return volatileTarget != null ? volatileTarget : stableTarget;
    }

    private boolean shouldIsolateNonPrincipalInflows(
            AsyncLifecycleBucket bucket,
            boolean touchedEligiblePrincipal,
            Set<String> touchedEligibleIdentities
    ) {
        if (!touchedEligiblePrincipal || bucket == null) {
            return false;
        }
        Set<String> remainingIdentities = bucket.knownAssetIdentities();
        return remainingIdentities.isEmpty()
                || remainingIdentities.stream().anyMatch(touchedEligibleIdentities::contains);
    }

    private void recordSideflowLpExitInflows(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            List<IndexedFlow> flows
    ) {
        for (IndexedFlow indexedFlow : flows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            PositionSnapshot before = flowSupport.snapshot(position);
            BigDecimal replayUnitPriceUsd = assetSupport.replayUnitPriceUsd(transaction, flow);
            if (replayUnitPriceUsd != null) {
                NormalizedTransaction.Flow pricedFlow = flowSupport.copyFlowWithQuantity(flow, flow.getQuantityDelta().abs());
                pricedFlow.setUnitPriceUsd(replayUnitPriceUsd);
                if (pricedFlow.getPriceSource() == null || pricedFlow.getPriceSource() == PriceSource.UNKNOWN) {
                    pricedFlow.setPriceSource(PriceSource.STABLECOIN);
                }
                flowSupport.applyBuyWithAcquisitionCost(
                        pricedFlow,
                        position,
                        pricedFlow.getQuantityDelta().abs().multiply(replayUnitPriceUsd, MC),
                        NormalizedTransactionType.REWARD_CLAIM
                );
                replayState.ledgerPointCollector().record(
                        transaction,
                        pricedFlow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.ACQUIRE
                );
                continue;
            }

            flowSupport.applyUnknownTransfer(flow, position);
            flowSupport.applyInboundShortfallSpotFallback(flow, position, before);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }
    }

    private void recordUnknownLpExitInflows(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            List<IndexedFlow> flows
    ) {
        for (IndexedFlow indexedFlow : flows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            PositionSnapshot before = flowSupport.snapshot(position);
            flowSupport.applyUnknownTransfer(flow, position);
            flowSupport.applyInboundShortfallSpotFallback(flow, position, before);
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.UNKNOWN
            );
        }
    }

    /**
     * Cycle/15 round 2: cross-asset LP receipt basis carry.
     *
     * <p>For multi-asset LP entries (e.g. BASE PancakeSwap V3 with WETH+USDC outbound), the
     * exit typically returns a single asset (e.g. USDC). Same-asset basis is withdrawn first,
     * then cross-asset pools sharing the same {@code lpCorrelationId} are drained
     * proportionally so their basis carries into the exit asset position. If no same-asset
     * pool matches (e.g. CAKE reward sideflow), routing falls back to the async-bucket /
     * sideflow logic.</p>
     */
    private boolean restoreInboundFromLpReceiptPool(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            PositionState position,
            PositionSnapshot before,
            ReplayExecutionState replayState,
            List<IndexedFlow> residualPrincipalInflows
    ) {
        if (transaction != null && transaction.getType() == NormalizedTransactionType.LP_FEE_CLAIM) {
            return false;
        }
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null || transaction.getCorrelationId() == null) {
            return false;
        }
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        String assetIdentity = assetSupport.continuityIdentity(transaction, flow);
        String corrId = transaction.getCorrelationId();

        LpReceiptBasisPoolKey sameKey = new LpReceiptBasisPoolKey(
                poolContext.universeId(),
                corrId,
                assetIdentity
        );
        LpReceiptBasisPool samePool = poolContext.pools().get(sameKey);
        if (samePool == null || samePool.getQtyHeld() == null || samePool.getQtyHeld().signum() <= 0) {
            return false;
        }

        BigDecimal sameHeldBefore = samePool.getQtyHeld();
        BigDecimal requestedQty = flow.getQuantityDelta().abs();
        var sameWithdraw = lpReceiptBasisPoolService.withdraw(samePool, requestedQty);
        poolContext.dirtyKeys().add(sameKey);
        if (sameWithdraw.withdrawnQty().signum() <= 0) {
            return false;
        }

        BigDecimal totalQty = sameWithdraw.withdrawnQty();
        BigDecimal totalBasis = sameWithdraw.withdrawnBasisUsd();
        // ADR-040 / BB-LP-CMETH-1: carry net lane from pool (already stored by LpReceiptEntryReplayHandler).
        // Without this, LP_EXIT restores net cost = market cost, inflating Net AVCO when Bug-B
        // propagated a sub-market net cost into the LP at entry time.
        BigDecimal totalNetBasis = sameWithdraw.withdrawnNetBasisUsd();
        BigDecimal totalUncovered = sameWithdraw.withdrawnUncoveredQty();

        // Drain cross-asset pools proportional to how much of the same-asset pool was
        // withdrawn. Withdraw on the pool service decrements qty/basis/uncovered atomically,
        // so the WETH pool's qty shrinks in lockstep with the USDC pool when each partial
        // exit is processed.
        BigDecimal proportion = sameHeldBefore.signum() > 0
                ? sameWithdraw.withdrawnQty().divide(sameHeldBefore, MC)
                : java.math.BigDecimal.ZERO;

        // Collect asset identities of other inbound TRANSFER flows in this transaction.
        // When a multi-asset CL exit returns both WETH and USDC simultaneously, each asset's
        // pool is drained by its own flow. Cross-pool carry must not touch pools whose asset
        // is directly returned — doing so would double-count their basis (once via cross-drain
        // here, once via the direct flow's own restoreInboundFromLpReceiptPool call).
        java.util.Set<String> directlyReturnedIdentities = new java.util.HashSet<>();
        for (NormalizedTransaction.Flow f : transaction.getFlows()) {
            if (f == null || f == flow || f.getRole() != NormalizedLegRole.TRANSFER
                    || f.getQuantityDelta() == null || f.getQuantityDelta().signum() <= 0) {
                continue;
            }
            String id = assetSupport.continuityIdentity(transaction, f);
            if (id != null) {
                directlyReturnedIdentities.add(id);
            }
        }

        boolean crossAssetBasisCarried = false;
        if (proportion.signum() > 0) {
            for (var entry : poolContext.pools().entrySet()) {
                LpReceiptBasisPoolKey key = entry.getKey();
                if (!corrId.equals(key.lpCorrelationId()) || sameKey.equals(key)) {
                    continue;
                }
                // Skip cross-pools whose asset is directly returned in this transaction —
                // those pools will be (or already were) drained by their own direct flow.
                if (directlyReturnedIdentities.contains(key.assetIdentity())) {
                    continue;
                }
                LpReceiptBasisPool crossPool = entry.getValue();
                if (crossPool == null || crossPool.getQtyHeld() == null
                        || crossPool.getQtyHeld().signum() <= 0) {
                    continue;
                }
                BigDecimal crossWithdrawQty = crossPool.getQtyHeld().multiply(proportion, MC);
                if (crossWithdrawQty.signum() <= 0) {
                    continue;
                }
                var crossWithdraw = lpReceiptBasisPoolService.withdraw(crossPool, crossWithdrawQty);
                poolContext.dirtyKeys().add(key);
                if (crossWithdraw.withdrawnBasisUsd().signum() > 0
                        || crossWithdraw.withdrawnUncoveredQty().signum() > 0) {
                    crossAssetBasisCarried = true;
                }
                totalBasis = totalBasis.add(crossWithdraw.withdrawnBasisUsd(), MC);
                totalNetBasis = totalNetBasis.add(crossWithdraw.withdrawnNetBasisUsd(), MC);
                totalUncovered = totalUncovered.add(crossWithdraw.withdrawnUncoveredQty(), MC);
            }
        }

        // When cross-asset basis was carried, the LP exit conceptually returns the entire
        // requested qty backed by the combined basis (same-asset pool basis + cross-asset
        // pool basis represent the unified LP position). Crediting only the same-asset
        // withdrawn qty and pushing the residual back into the principal inflow queue would
        // strand the cross-asset basis on a single same-asset chunk while the residual gets
        // sideflow ACQUIRE/UNKNOWN. Instead, absorb the residual into this REALLOCATE_IN so
        // the cross-asset basis covers the full LP return.
        BigDecimal residualSameAssetQty = sameWithdraw.residualQty() == null
                ? BigDecimal.ZERO
                : sameWithdraw.residualQty();
        if (crossAssetBasisCarried && residualSameAssetQty.signum() > 0) {
            totalQty = totalQty.add(residualSameAssetQty, MC);
            residualSameAssetQty = BigDecimal.ZERO;
        }

        // U-3: when NO cross-asset basis was carried, this is a same-asset stablecoin continuity
        // carry — cap a USD-stablecoin underlying at the $1 peg. A cross-asset exit (e.g. a WETH/USDC
        // pool fully returned as USDC) legitimately carries combined basis above $1/unit and must NOT
        // be capped, or the cross-asset basis would be destroyed and a gain fabricated.
        BigDecimal effectiveBasis = totalBasis;
        BigDecimal effectiveNetBasis = totalNetBasis;
        if (!crossAssetBasisCarried && isAllStableMultiTokenBalancerExit(transaction)) {
            // R2 / R6a all-stablecoin per-lane cap: on an all-stable Balancer exit there is NO
            // volatile absorber, so peg-capping every leg at $1 would strand the above-$1 compounded
            // basis (destroying ~$5.85 on the R6a boosted stable pool). Because each stablecoin
            // withdraws its OWN per-asset pool, Σ carried == Σ poolBasis == combinedBasis exactly
            // when the real pool basis is carried. The returned-qty excess (compounded yield beyond
            // the pool qty) surfaces as a residual and is booked zero-net downstream, so no basis is
            // fabricated. Only the net ≤ tax invariant is enforced (below). This intentionally skips
            // pegCappedStablecoinCarryBasis for this narrow, correlation-gated case.
            effectiveBasis = totalBasis;
            effectiveNetBasis = totalNetBasis;
        } else if (!crossAssetBasisCarried) {
            BigDecimal coveredForCap = totalQty.subtract(totalUncovered, MC);
            effectiveBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(), coveredForCap, totalBasis);
            // Apply same-asset peg cap to the net lane symmetrically.
            effectiveNetBasis = flowSupport.pegCappedStablecoinCarryBasis(
                    position.assetKey(), coveredForCap, totalNetBasis);

            // R2: peg-cap gating — if stablecoin basis was capped, inject the surplus into
            // directly-returned volatile sibling pools so combined LP basis is conserved.
            // The volatile principal (e.g. WETH) drains its own pool later and absorbs the surplus.
            BigDecimal taxSurplus = totalBasis.subtract(effectiveBasis, MC).max(BigDecimal.ZERO);
            BigDecimal netSurplus = totalNetBasis.subtract(effectiveNetBasis, MC).max(BigDecimal.ZERO);
            if (taxSurplus.signum() > 0) {
                injectPegCapSurplusToSiblingPool(
                        corrId, sameKey, taxSurplus, netSurplus,
                        directlyReturnedIdentities, poolContext
                );
            }
        }
        // R2: net ≤ tax invariant — clamp effective net so it never exceeds effective tax.
        if (effectiveNetBasis.compareTo(effectiveBasis) > 0) {
            effectiveNetBasis = effectiveBasis;
        }
        BigDecimal avco = effectiveBasis.signum() > 0 && totalQty.signum() > 0
                ? effectiveBasis.divide(totalQty, MC)
                : null;
        // BB-LP-CMETH-1: net lane uses pool's stored netBasisHeldUsd, not market basis, so
        // sub-market net cost from Bug-B is faithfully carried through LP.
        // D3: use the LP-pool restore that does NOT floor the net lane up to peg — flooring the
        // reward-discounted pooled net up to qty×$1 fabricates net basis above the pool net and
        // breaks the net ≤ tax invariant (450450 / 2984825 / 72791605).
        flowSupport.restoreLpReceiptPoolBasis(totalQty, position, effectiveBasis, effectiveNetBasis, totalUncovered, avco);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
        if (residualSameAssetQty.signum() > 0) {
            residualPrincipalInflows.add(new IndexedFlow(
                    indexedFlow.index(),
                    flowSupport.copyFlowWithQuantity(flow, residualSameAssetQty)
            ));
        }
        return true;
    }

    /**
     * R2: When a stablecoin's pool basis is peg-capped (basis > qty×$1), the excess would
     * otherwise be discarded. This method re-injects the surplus into the first directly-returned
     * volatile sibling pool so the combined LP basis is conserved.
     *
     * <p>Only fires when {@code crossAssetBasisCarried = false} (same-asset carry with peg-cap).
     * In a dual-token exit (WETH+USDC both returned), WETH is in {@code directlyReturnedIdentities}
     * and its pool receives the USDC peg-cap surplus. When WETH drains its own pool later in the
     * same settlement pass, the injected surplus is included proportionally — no double-count.</p>
     *
     * <p>All-stablecoin exits (no volatile sibling): no injection occurs; excess is consumed.
     * This is acceptable for stable/stable pools where compounded yield is minimal.</p>
     */
    private void injectPegCapSurplusToSiblingPool(
            String corrId,
            LpReceiptBasisPoolKey sameKey,
            BigDecimal taxSurplus,
            BigDecimal netSurplus,
            Set<String> directlyReturnedIdentities,
            LpReceiptBasisPoolReplayContext poolContext
    ) {
        if (taxSurplus.signum() <= 0 || directlyReturnedIdentities.isEmpty()) {
            return;
        }
        for (var entry : poolContext.pools().entrySet()) {
            LpReceiptBasisPoolKey key = entry.getKey();
            if (!corrId.equals(key.lpCorrelationId()) || sameKey.equals(key)) {
                continue;
            }
            // Only inject into pools whose asset is directly returned as principal in this tx.
            // These pools will be drained later in the same settlement pass and absorb the surplus.
            if (!directlyReturnedIdentities.contains(key.assetIdentity())) {
                continue;
            }
            LpReceiptBasisPool sibling = entry.getValue();
            if (sibling == null) {
                continue;
            }
            BigDecimal currentTax = sibling.getBasisHeldUsd() != null ? sibling.getBasisHeldUsd() : BigDecimal.ZERO;
            BigDecimal currentNet = sibling.getNetBasisHeldUsd() != null ? sibling.getNetBasisHeldUsd() : BigDecimal.ZERO;
            // net ≤ tax: injected net must not push sibling net above sibling tax + injected tax.
            BigDecimal clampedNetSurplus = netSurplus.min(taxSurplus);
            sibling.setBasisHeldUsd(currentTax.add(taxSurplus, MC));
            sibling.setNetBasisHeldUsd(currentNet.add(clampedNetSurplus, MC));
            poolContext.dirtyKeys().add(key);
            log.info("R2 peg-cap surplus carry: corrId={} stablePool={} volatilePool={} taxSurplus={} netSurplus={}",
                    corrId, sameKey.assetIdentity(), key.assetIdentity(), taxSurplus, clampedNetSurplus);
            return; // inject into the first eligible volatile sibling only
        }
    }

    /**
     * D1 (R2 Option-B2): dual-token in-range / rebalancing exits return MORE of one asset than its
     * own LP-receipt pool held (a residual with no same-asset basis) while a sibling pool retains
     * un-drained basis (the other asset returned LESS than held). The old path booked that residual
     * as a market-priced {@code ACQUIRE} (income) and let {@code drainAll} destroy the sibling
     * leftover. Under Option B the combined LP basis is conserved: the residual absorbs the sibling
     * leftover as {@code REALLOCATE_IN} return-of-capital. Returned stablecoin residuals stay
     * peg-capped ($1) when a volatile absorber is present; the volatile leg(s) absorb the remainder.
     *
     * <p>Protocol-agnostic — keyed only on the residual + remaining pool basis (never a tx hash /
     * wallet), so it applies uniformly to Uniswap V3, Velodrome/Aerodrome Slipstream and
     * PancakeSwap V3. Gated on principal-close evidence, so a partial proportional exit (no residual,
     * pools still open) is untouched. Reconciled with R6a: an all-stable Balancer exit returns each
     * stable at/under its pool held (no residual), so it never enters this path.</p>
     *
     * @return the residual inflows NOT consumed here (unchanged when there is nothing to carry).
     */
    private List<IndexedFlow> carryResidualPrincipalFromRemainingPools(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            List<IndexedFlow> residualPrincipalInflows
    ) {
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        String corrId = transaction.getCorrelationId();
        if (poolContext == null || corrId == null || residualPrincipalInflows.isEmpty()) {
            return residualPrincipalInflows;
        }
        // Only a full/closing exit re-allocates the whole remaining combined basis. A partial exit
        // with no close evidence keeps un-exited basis in the pool for the next exit.
        if (!hasPrincipalCloseEvidence(transaction)
                && transaction.getType() != NormalizedTransactionType.LP_EXIT_FINAL
                && !replayState.lpReceiptLifecycleClosed(corrId)) {
            return residualPrincipalInflows;
        }

        BigDecimal remainingTax = BigDecimal.ZERO;
        BigDecimal remainingNet = BigDecimal.ZERO;
        List<LpReceiptBasisPoolKey> poolKeys = new ArrayList<>();
        for (var entry : poolContext.pools().entrySet()) {
            if (!corrId.equals(entry.getKey().lpCorrelationId())) {
                continue;
            }
            LpReceiptBasisPool pool = entry.getValue();
            if (pool == null) {
                continue;
            }
            BigDecimal tax = pool.getBasisHeldUsd() == null ? BigDecimal.ZERO : pool.getBasisHeldUsd();
            BigDecimal net = pool.getNetBasisHeldUsd() == null ? tax : pool.getNetBasisHeldUsd();
            remainingTax = remainingTax.add(tax, MC);
            remainingNet = remainingNet.add(net, MC);
            poolKeys.add(entry.getKey());
        }
        if (remainingTax.signum() <= 0) {
            return residualPrincipalInflows; // nothing un-drained to carry
        }
        // net can never exceed tax at the combined level.
        if (remainingNet.compareTo(remainingTax) > 0) {
            remainingNet = remainingTax;
        }

        List<IndexedFlow> stableResiduals = new ArrayList<>();
        List<IndexedFlow> volatileResiduals = new ArrayList<>();
        for (IndexedFlow indexedFlow : residualPrincipalInflows) {
            if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(indexedFlow.flow().getAssetSymbol())) {
                stableResiduals.add(indexedFlow);
            } else {
                volatileResiduals.add(indexedFlow);
            }
        }
        boolean hasVolatileAbsorber = !volatileResiduals.isEmpty();

        // Phase 1: stablecoin residuals are peg-capped at the $1 face value when a volatile leg is
        // present to absorb the remainder. Without a volatile absorber they fall into the absorber
        // group below so the combined basis is conserved (Option B — IL realizes via AVCO on sale).
        if (hasVolatileAbsorber) {
            for (IndexedFlow indexedFlow : stableResiduals) {
                BigDecimal qty = indexedFlow.flow().getQuantityDelta().abs();
                BigDecimal taxAlloc = qty.min(remainingTax);
                BigDecimal netAlloc = qty.min(remainingNet).min(taxAlloc);
                bookResidualReallocateIn(transaction, replayState, indexedFlow, taxAlloc, netAlloc);
                remainingTax = remainingTax.subtract(taxAlloc, MC).max(BigDecimal.ZERO);
                remainingNet = remainingNet.subtract(netAlloc, MC).max(BigDecimal.ZERO);
            }
        }

        // Phase 2: absorbers take the remainder split proportionally by returned value; the last
        // absorber takes the exact remainder so Σ carried == combined basis (no rounding leak).
        List<IndexedFlow> absorbers = hasVolatileAbsorber ? volatileResiduals : stableResiduals;
        if (!absorbers.isEmpty()) {
            BigDecimal totalWeight = BigDecimal.ZERO;
            for (IndexedFlow indexedFlow : absorbers) {
                totalWeight = totalWeight.add(residualWeight(transaction, indexedFlow), MC);
            }
            BigDecimal distributedTax = BigDecimal.ZERO;
            BigDecimal distributedNet = BigDecimal.ZERO;
            for (int i = 0; i < absorbers.size(); i++) {
                IndexedFlow indexedFlow = absorbers.get(i);
                BigDecimal taxAlloc;
                BigDecimal netAlloc;
                if (i == absorbers.size() - 1) {
                    taxAlloc = remainingTax.subtract(distributedTax, MC).max(BigDecimal.ZERO);
                    netAlloc = remainingNet.subtract(distributedNet, MC).max(BigDecimal.ZERO);
                } else {
                    BigDecimal share = totalWeight.signum() > 0
                            ? residualWeight(transaction, indexedFlow).divide(totalWeight, MC)
                            : BigDecimal.ZERO;
                    taxAlloc = remainingTax.multiply(share, MC);
                    netAlloc = remainingNet.multiply(share, MC);
                }
                netAlloc = netAlloc.min(taxAlloc);
                bookResidualReallocateIn(transaction, replayState, indexedFlow, taxAlloc, netAlloc);
                distributedTax = distributedTax.add(taxAlloc, MC);
                distributedNet = distributedNet.add(netAlloc, MC);
            }
        }

        // Combined basis fully re-allocated on close — zero the correlation pools (both lanes).
        for (LpReceiptBasisPoolKey key : poolKeys) {
            LpReceiptBasisPool pool = poolContext.pools().get(key);
            if (pool == null) {
                continue;
            }
            pool.setQtyHeld(BigDecimal.ZERO);
            pool.setBasisHeldUsd(BigDecimal.ZERO);
            pool.setNetBasisHeldUsd(BigDecimal.ZERO);
            pool.setUncoveredQtyHeld(BigDecimal.ZERO);
            poolContext.dirtyKeys().add(key);
        }
        return List.of();
    }

    /**
     * D1: books a rebalancing-residual principal leg as {@code REALLOCATE_IN} carrying the supplied
     * (authoritative) tax/net allocation — never a market-priced ACQUIRE. Skips the tax-lane peg
     * floor because the allocation is already peg-cap-aware.
     */
    private void bookResidualReallocateIn(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState,
            IndexedFlow indexedFlow,
            BigDecimal taxAlloc,
            BigDecimal netAlloc
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        BigDecimal qty = flow.getQuantityDelta().abs();
        BigDecimal avco = taxAlloc.signum() > 0 && qty.signum() > 0
                ? taxAlloc.divide(qty, MC)
                : null;
        flowSupport.restoreLpReceiptPoolBasis(qty, position, taxAlloc, netAlloc, BigDecimal.ZERO, avco, false);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    /** D1: allocation weight for an absorbing residual — returned USD value when priced, else qty. */
    private BigDecimal residualWeight(NormalizedTransaction transaction, IndexedFlow indexedFlow) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        BigDecimal qty = flow.getQuantityDelta().abs();
        BigDecimal price = assetSupport.replayUnitPriceUsd(transaction, flow);
        return price != null ? qty.multiply(price, MC) : qty;
    }

    private boolean isLpExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    /**
     * R2 / R6a: {@code true} when this exit is an all-stablecoin, multi-token Balancer V3 exit — i.e.
     * a Balancer-correlated exit whose directly-returned principal legs (inbound TRANSFER, non
     * LP-RECEIPT) are ALL USD stablecoins and there are at least two distinct principal legs.
     *
     * <p>These exits have no volatile absorber, so the standard $1 peg cap would destroy the
     * compounded above-$1 basis. When this returns {@code true}, {@code restoreInboundFromLpReceiptPool}
     * carries the real per-asset pool basis so Σ carried == combinedBasis exactly (no fabrication, no
     * destruction). Gated tightly on the {@code :balancerv3:} correlation so single-stable continuity
     * carries and non-Balancer LP exits keep the existing peg-cap behaviour.</p>
     */
    private boolean isAllStableMultiTokenBalancerExit(NormalizedTransaction transaction) {
        if (transaction == null || !isLpExitType(transaction.getType())) {
            return false;
        }
        String corrId = transaction.getCorrelationId();
        if (corrId == null || !corrId.toLowerCase(java.util.Locale.ROOT).contains(":balancerv3:")) {
            return false;
        }
        if (transaction.getFlows() == null) {
            return false;
        }
        int stablePrincipalLegs = 0;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (Boolean.TRUE.equals(flow.getLpReceipt())
                    || AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())) {
                continue;
            }
            // A non-stable directly-returned principal leg disqualifies the all-stable path.
            if (!CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
                return false;
            }
            stablePrincipalLegs++;
        }
        return stablePrincipalLegs >= 2;
    }

    /**
     * Harvest-only / downgraded rows must not drain {@code lp_receipt_basis_pools}. Principal
     * close requires outbound receipt evidence (negative LP-RECEIPT or composite receipt leg).
     */
    boolean hasPrincipalCloseEvidence(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return false;
        }
        String compositeReceiptIdentity = pendingTransferKeyFactory.lpCompositeReceiptIdentity(transaction);
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())
                    || Boolean.TRUE.equals(flow.getLpReceipt())) {
                return true;
            }
            if (compositeReceiptIdentity != null
                    && compositeReceiptIdentity.equals(assetSupport.continuityIdentity(transaction, flow))) {
                return true;
            }
        }
        return false;
    }
}
