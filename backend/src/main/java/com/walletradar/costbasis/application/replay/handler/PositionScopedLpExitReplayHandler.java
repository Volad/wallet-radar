package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.costbasis.application.replay.model.AsyncLifecycleBucket;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class PositionScopedLpExitReplayHandler {

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
                flowSupport.restoreToPosition(
                        sameAssetCarry.quantity(),
                        position,
                        sameAssetCarry.costBasisUsd(),
                        sameAssetCarry.uncoveredQuantity(),
                        sameAssetCarry.avco()
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
            return;
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
            return;
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
            return;
        }

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0) {
            recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return;
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
            return;
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
            return;
        }
        if (bucket.remainingUncoveredQuantity().signum() > 0) {
            recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
            recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return;
        }

        recordUnknownLpExitInflows(transaction, replayState, allocatableInflows);
        recordUnknownLpExitInflows(transaction, replayState, unknownOnlyInflows);
        bucket.clearAll();
        replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
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
                flowSupport.applyBuy(pricedFlow, position);
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
        BigDecimal totalUncovered = sameWithdraw.withdrawnUncoveredQty();

        // Drain cross-asset pools proportional to how much of the same-asset pool was
        // withdrawn. Withdraw on the pool service decrements qty/basis/uncovered atomically,
        // so the WETH pool's qty shrinks in lockstep with the USDC pool when each partial
        // exit is processed.
        BigDecimal proportion = sameHeldBefore.signum() > 0
                ? sameWithdraw.withdrawnQty().divide(sameHeldBefore, MC)
                : java.math.BigDecimal.ZERO;
        boolean crossAssetBasisCarried = false;
        if (proportion.signum() > 0) {
            for (var entry : poolContext.pools().entrySet()) {
                LpReceiptBasisPoolKey key = entry.getKey();
                if (!corrId.equals(key.lpCorrelationId()) || sameKey.equals(key)) {
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

        BigDecimal avco = totalBasis.signum() > 0 && totalQty.signum() > 0
                ? totalBasis.divide(totalQty, MC)
                : null;
        flowSupport.restoreToPosition(totalQty, position, totalBasis, totalUncovered, avco);
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

    private boolean isLpExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }
}
