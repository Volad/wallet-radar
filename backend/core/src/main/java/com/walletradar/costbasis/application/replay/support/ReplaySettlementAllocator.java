package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.PositionStore;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

@Component
public class ReplaySettlementAllocator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public ReplaySettlementAllocator(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
    }

    public void applyFallbackSettlementFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionStore positions,
            LedgerPointCollector ledgerPointCollector
    ) {
        if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
            return;
        }
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = positions.position(assetKey);
        position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot before = flowSupport.snapshot(position);
        if (flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.FEE) {
            flowSupport.applyFee(flow, position);
            ledgerPointCollector.record(
                    transaction,
                    flow,
                    flowSupport.flowIndex(transaction, flow),
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
            return;
        }
        flowSupport.applyUnknownTransfer(flow, position);

        ledgerPointCollector.record(
            transaction,
            flow,
            flowSupport.flowIndex(transaction, flow),
            position.assetKey(),
            before,
            position,
            AssetLedgerPoint.BasisEffect.UNKNOWN
        );
    }

    public void allocateIndexedSettlementByQuantity(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        allocateSettlementByQuantity(transaction, flows, positions, totalCostBasisUsd, ledgerPointCollector);
    }

    public void allocateIndexedSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        allocateSettlementByKnownValue(transaction, flows, positions, totalCostBasisUsd, ledgerPointCollector);
    }

    public void allocateIndexedSettlementByReplayKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalValue = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            BigDecimal unitPriceUsd = assetSupport.replayUnitPriceUsd(transaction, indexedFlow.flow());
            totalValue = totalValue.add(indexedFlow.flow().getQuantityDelta().abs().multiply(unitPriceUsd, MC), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            BigDecimal unitPriceUsd = assetSupport.replayUnitPriceUsd(transaction, indexedFlow.flow());
            BigDecimal value = indexedFlow.flow().getQuantityDelta().abs().multiply(unitPriceUsd, MC);
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(value.divide(totalValue, MC), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    public void restoreSettlementPosition(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            PositionStore positions,
            BigDecimal allocatedCost,
            LedgerPointCollector ledgerPointCollector
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = positions.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        flowSupport.restoreToPosition(flow, position, avco, allocatedCost);
        ledgerPointCollector.record(
                transaction,
                flow,
                indexedFlow.index(),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );
    }

    private void allocateSettlementByQuantity(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalQuantity = totalQuantity.add(indexedFlow.flow().getQuantityDelta().abs(), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            BigDecimal quantity = indexedFlow.flow().getQuantityDelta().abs();
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(quantity.divide(totalQuantity, MC), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private void allocateSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalValue = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalValue = totalValue.add(
                    indexedFlow.flow().getQuantityDelta().abs().multiply(indexedFlow.flow().getUnitPriceUsd(), MC),
                    MC
            );
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            BigDecimal value = indexedFlow.flow().getQuantityDelta().abs().multiply(indexedFlow.flow().getUnitPriceUsd(), MC);
            BigDecimal allocatedCost = index == flows.size() - 1
                    ? remainingCost
                    : totalCostBasisUsd.multiply(value.divide(totalValue, MC), MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, ledgerPointCollector);
        }
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }
}
