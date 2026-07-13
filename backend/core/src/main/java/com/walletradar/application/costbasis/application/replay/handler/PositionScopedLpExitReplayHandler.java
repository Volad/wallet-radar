package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.application.costbasis.support.LpReceiptSymbolSupport;
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
import java.util.LinkedHashSet;
import java.util.List;
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
        // Only skip the burned LP receipt leg on true exits. Mint-shaped LP_EXIT rows carry
        // negative underlying deposits that must reach the composite lp: bucket first.
        String receiptIdentity = pendingTransferKeyFactory.lpCompositeReceiptIdentity(transaction);
        if (receiptIdentity == null) {
            return false;
        }
        String flowIdentity = assetSupport.continuityIdentity(transaction, flow);
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
                // R1: fee income at LP exit — zero-cost acquisition (income stays implicit in AVCO).
                // Net lane: $0 cost (ZeroCostAcquisitionSupport recognises LP_FEE_CLAIM as zero-net).
                // Tax lane: FMV at exit time (applied by the replay engine using unit price if available).
                flowSupport.applyBuyWithAcquisitionCost(
                        flow,
                        position,
                        BigDecimal.ZERO,
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
        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(transaction, transaction.getCorrelationId());
        if (receiptKey == null) {
            return;
        }
        PositionState receiptPosition = replayState.position(receiptKey);
        if (receiptPosition.quantity().signum() <= 0) {
            drainAllLpReceiptPoolsForCorrelation(transaction.getCorrelationId(), replayState);
            return;
        }
        PositionSnapshot before = flowSupport.snapshot(receiptPosition);
        receiptPosition.setQuantity(BigDecimal.ZERO);
        receiptPosition.setTotalCostBasisUsd(BigDecimal.ZERO);
        receiptPosition.setNetTotalCostBasisUsd(BigDecimal.ZERO);
        receiptPosition.setUncoveredQuantity(BigDecimal.ZERO);
        receiptPosition.setPerWalletAvco(null);
        receiptPosition.setPerWalletNetAvco(null);
        IndexedFlow markerFlow = null;
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() >= 0
                    || !receiptKey.assetSymbol().equalsIgnoreCase(flow.getAssetSymbol())) {
                continue;
            }
            markerFlow = indexedFlow;
            break;
        }
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
        drainAllLpReceiptPoolsForCorrelation(transaction.getCorrelationId(), replayState);
    }

    private void drainAllLpReceiptPoolsForCorrelation(
            String correlationId,
            ReplayExecutionState replayState
    ) {
        LpReceiptBasisPoolReplayContext poolContext = replayState.lpReceiptBasisPoolContext();
        if (poolContext == null || correlationId == null) {
            return;
        }
        for (var entry : poolContext.pools().entrySet()) {
            if (!correlationId.equals(entry.getKey().lpCorrelationId())) {
                continue;
            }
            LpReceiptBasisPool pool = entry.getValue();
            if (pool == null) {
                continue;
            }
            BigDecimal danglingNet = pool.getNetBasisHeldUsd();
            if (danglingNet != null && danglingNet.signum() != 0) {
                // R3: dangling net basis (reward-discounted lane) was not carried during per-flow
                // restore — zero it out here so pools are fully clean after close.
                // Root cause: peg-cap on stable lane stranded net basis; R2 injects tax excess to
                // volatile sibling but this catch-all ensures any residual is not silently orphaned.
                log.debug("R3 drain: zeroing dangling netBasisHeldUsd={} on pool key={}", danglingNet, entry.getKey());
            }
            pool.setQtyHeld(BigDecimal.ZERO);
            pool.setBasisHeldUsd(BigDecimal.ZERO);
            pool.setNetBasisHeldUsd(BigDecimal.ZERO);
            pool.setUncoveredQtyHeld(BigDecimal.ZERO);
            poolContext.dirtyKeys().add(entry.getKey());
        }
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
        if (!crossAssetBasisCarried) {
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
        // BB-LP-CMETH-1: 6-arg restore — net lane uses pool's stored netBasisHeldUsd,
        // not market basis, so sub-market net cost from Bug-B is faithfully carried through LP.
        flowSupport.restoreToPosition(totalQty, position, effectiveBasis, effectiveNetBasis, totalUncovered, avco);
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

    private boolean isLpExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
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
            if (AccountingAssetFamilySupport.isLpReceiptSymbol(flow.getAssetSymbol())) {
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
