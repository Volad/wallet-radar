package com.walletradar.costbasis.application.replay.handler;

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
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

@Component
public class GenericAsyncLifecycleReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplaySettlementAllocator settlementAllocator;

    public GenericAsyncLifecycleReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplaySettlementAllocator settlementAllocator
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.settlementAllocator = settlementAllocator;
    }

    public boolean isAsyncLifecycleRequestOutbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() < 0
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && (transaction.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                || transaction.getType() == NormalizedTransactionType.LP_EXIT_REQUEST
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW_REQUEST);
    }

    public boolean isAsyncLifecycleSettlementInbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() > 0
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && (transaction.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                || transaction.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT
                || transaction.getType() == NormalizedTransactionType.STAKING_WITHDRAW);
    }

    public boolean isPositionScopedLpEntryOutbound(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta().signum() < 0
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                // Multi-asset same-tx LP mint (Curve/Balancer) uses composite lp: bucket, not async lifecycle.
                && LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(transaction);
    }

    public void applyAsyncLifecycleRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        applyAsyncLifecycleRequest(transaction, flow, position, replayState, assetSupport.assetKey(transaction, flow).assetIdentity());
    }

    public void applyAsyncLifecycleRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState,
            String bucketIdentity
    ) {
        CarryTransfer carry = flowSupport.removeFromPosition(flow, position);
        replayState.asyncLifecycleBucket(transaction.getCorrelationId()).add(bucketIdentity, carry);
    }

    public void applyAsyncLifecycleSettlement(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());
        String assetIdentity = assetSupport.assetKey(transaction, flow).assetIdentity();
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(assetIdentity, requestedQuantity, position.assetKey());
        if (sameAssetCarry != null) {
            flowSupport.restoreToPosition(
                    sameAssetCarry.quantity(),
                    position,
                    sameAssetCarry.costBasisUsd(),
                    sameAssetCarry.uncoveredQuantity(),
                    sameAssetCarry.avco()
            );
            BigDecimal residualQuantity = requestedQuantity.subtract(sameAssetCarry.quantity(), MC);
            if (residualQuantity.signum() <= 0) {
                if (bucket.isEmpty()) {
                    replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
                }
                return;
            }
            requestedQuantity = residualQuantity;
        }

        BigDecimal remainingCost = bucket.remainingCostBasisUsd();
        if (remainingCost.signum() <= 0 || bucket.remainingUncoveredQuantity().signum() > 0) {
            flowSupport.applyUnknownTransfer(flowSupport.copyFlowWithQuantity(flow, requestedQuantity), position);
            return;
        }
        BigDecimal avco = remainingCost.divide(requestedQuantity, MC);
        bucket.clearAll();
        flowSupport.restoreToPosition(requestedQuantity, position, remainingCost, BigDecimal.ZERO, avco);
        replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
    }

    public void applyAsyncLpExitSettlement(
            NormalizedTransaction transaction,
            ReplayExecutionState replayState
    ) {
        if (transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank()) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                settlementAllocator.applyFallbackSettlementFlow(
                        transaction,
                        flow,
                        replayState.positions(),
                        replayState.ledgerPointCollector()
                );
            }
            return;
        }

        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());
        List<IndexedFlow> principalInflows = new ArrayList<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
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
                        flowSupport.flowIndex(transaction, flow),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.TRANSFER && flow.getQuantityDelta().signum() > 0) {
                CarryTransfer sameAssetCarry = bucket.takeSameAssetCarry(
                        assetKey.assetIdentity(),
                        flow.getQuantityDelta().abs(),
                        position.assetKey()
                );
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
                        principalInflows.add(new IndexedFlow(
                                indexedFlow.index(),
                                flowSupport.copyFlowWithQuantity(flow, residualQuantity)
                        ));
                    }
                } else {
                    principalInflows.add(indexedFlow);
                }
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

        if (principalInflows.isEmpty()) {
            if (bucket.isEmpty()) {
                replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            }
            return;
        }

        BigDecimal remainingCostBasis = bucket.remainingCostBasisUsd();
        if (remainingCostBasis.signum() <= 0 || bucket.remainingUncoveredQuantity().signum() > 0) {
            recordUnknownLpExitInflows(transaction, replayState, principalInflows);
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return;
        }

        if (assetSupport.allSameAsset(principalInflows.stream().map(IndexedFlow::flow).toList(), transaction)) {
            settlementAllocator.allocateIndexedSettlementByQuantity(
                    transaction,
                    principalInflows,
                    replayState.positions(),
                    remainingCostBasis,
                    replayState.ledgerPointCollector()
            );
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return;
        }

        if (assetSupport.allHaveKnownPrices(principalInflows.stream().map(IndexedFlow::flow).toList())) {
            settlementAllocator.allocateIndexedSettlementByKnownValue(
                    transaction,
                    principalInflows,
                    replayState.positions(),
                    remainingCostBasis,
                    replayState.ledgerPointCollector()
            );
            bucket.clearAll();
            replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
            return;
        }

        recordUnknownLpExitInflows(transaction, replayState, principalInflows);
        bucket.clearAll();
        replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
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
}
