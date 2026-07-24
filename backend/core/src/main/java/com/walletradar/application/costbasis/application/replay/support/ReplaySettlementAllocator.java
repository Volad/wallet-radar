package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
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

    /**
     * ADR-040 net-carry conservation (async redeem): net-aware peer of
     * {@link #allocateIndexedSettlementByQuantity(NormalizedTransaction, List, PositionStore, BigDecimal, LedgerPointCollector)}.
     * Distributes BOTH the tax total and the receipt's Σ net total across the returned legs by the
     * SAME quantity weights, so a reward-discounted receipt carries its net basis back on redeem
     * (never re-seeds net = tax). {@code net ≤ tax} enforced per leg.
     */
    public void allocateIndexedSettlementByQuantity(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            BigDecimal totalNetCostBasisUsd,
            LedgerPointCollector ledgerPointCollector
    ) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalQuantity = totalQuantity.add(indexedFlow.flow().getQuantityDelta().abs(), MC);
        }
        BigDecimal remainingCost = totalCostBasisUsd;
        BigDecimal remainingNetCost = totalNetCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            boolean last = index == flows.size() - 1;
            BigDecimal quantity = indexedFlow.flow().getQuantityDelta().abs();
            BigDecimal weight = totalQuantity.signum() == 0 ? BigDecimal.ZERO : quantity.divide(totalQuantity, MC);
            BigDecimal allocatedCost = last ? remainingCost : totalCostBasisUsd.multiply(weight, MC);
            BigDecimal allocatedNetCost = last ? remainingNetCost : totalNetCostBasisUsd.multiply(weight, MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingNetCost = remainingNetCost.subtract(allocatedNetCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, allocatedNetCost, ledgerPointCollector);
        }
    }

    /**
     * ADR-040 net-carry conservation (async redeem): net-aware peer of
     * {@link #allocateIndexedSettlementByKnownValue(NormalizedTransaction, List, PositionStore, BigDecimal, LedgerPointCollector)}.
     * Splits BOTH lanes by the SAME market-value weights.
     */
    public void allocateIndexedSettlementByKnownValue(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            PositionStore positions,
            BigDecimal totalCostBasisUsd,
            BigDecimal totalNetCostBasisUsd,
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
        BigDecimal remainingNetCost = totalNetCostBasisUsd;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            boolean last = index == flows.size() - 1;
            BigDecimal value = indexedFlow.flow().getQuantityDelta().abs().multiply(indexedFlow.flow().getUnitPriceUsd(), MC);
            BigDecimal weight = totalValue.signum() == 0 ? BigDecimal.ZERO : value.divide(totalValue, MC);
            BigDecimal allocatedCost = last ? remainingCost : totalCostBasisUsd.multiply(weight, MC);
            BigDecimal allocatedNetCost = last ? remainingNetCost : totalNetCostBasisUsd.multiply(weight, MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingNetCost = remainingNetCost.subtract(allocatedNetCost, MC);
            restoreSettlementPosition(transaction, indexedFlow, positions, allocatedCost, allocatedNetCost, ledgerPointCollector);
        }
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

    /**
     * ADR-040 net-carry conservation (async redeem): net-aware peer of
     * {@link #restoreSettlementPosition(NormalizedTransaction, IndexedFlow, PositionStore, BigDecimal, LedgerPointCollector)}.
     *
     * <p>Restores a returned settlement leg carrying BOTH lanes independently — tax =
     * {@code allocatedCost}, net = {@code allocatedNetCost} (Σ receipt net share). Routes through
     * {@link ReplayFlowSupport#restoreLpReceiptPoolBasis} with {@code applyTaxPegFloor=true}: the tax
     * lane keeps the exact R-3* below-peg floor of the legacy tax-only restore (byte-stable tax
     * behaviour), while the net lane is carried WITHOUT the peg floor (D3 discipline) so a
     * reward-discounted receipt redeemed into a USD stablecoin is not silently re-inflated back to
     * peg. {@code 0 ≤ net ≤ tax} is enforced (here and again inside the restore).</p>
     */
    public void restoreSettlementPosition(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            PositionStore positions,
            BigDecimal allocatedCost,
            BigDecimal allocatedNetCost,
            LedgerPointCollector ledgerPointCollector
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = positions.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        BigDecimal netCost = clampNetToTax(allocatedNetCost, allocatedCost);
        flowSupport.restoreLpReceiptPoolBasis(quantity, position, allocatedCost, netCost, BigDecimal.ZERO, avco, true);
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

    /** Enforces the ADR-040 global gate {@code 0 ≤ net ≤ tax} on a returned settlement leg. */
    private static BigDecimal clampNetToTax(BigDecimal netCost, BigDecimal taxCost) {
        BigDecimal net = netCost == null ? BigDecimal.ZERO : netCost;
        if (net.signum() < 0) {
            net = BigDecimal.ZERO;
        }
        if (taxCost != null && net.compareTo(taxCost) > 0) {
            net = taxCost;
        }
        return net;
    }
}
