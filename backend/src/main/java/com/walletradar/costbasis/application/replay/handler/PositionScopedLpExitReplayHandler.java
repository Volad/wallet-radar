package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AsyncLifecycleBucket;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
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

    public PositionScopedLpExitReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplaySettlementAllocator settlementAllocator
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.settlementAllocator = settlementAllocator;
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
            return flow.getQuantityDelta().signum() > 0;
        }
        return isLpExitType(transaction.getType()) && flow.getQuantityDelta().signum() < 0;
    }

    public void apply(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());
        List<IndexedFlow> residualPrincipalInflows = new ArrayList<>();
        List<IndexedFlow> deferredCrossAssetInflows = new ArrayList<>();
        Set<String> eligibleIdentities = bucket.knownAssetIdentities();
        Set<String> touchedEligibleIdentities = new LinkedHashSet<>();
        boolean touchedEligiblePrincipal = false;

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
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() < 0) {
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

        if (shouldIsolateNonPrincipalInflows(bucket, touchedEligiblePrincipal, touchedEligibleIdentities)) {
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

    private boolean isLpExitType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }
}
