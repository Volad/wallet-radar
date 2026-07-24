package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class GmxLpEntryReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public GmxLpEntryReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
    }

    public boolean isGmxLpEntryRequest(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                && "GMX".equalsIgnoreCase(transaction.getProtocolName())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && supportsGmxLpEntryRequestPattern(transaction);
    }

    public boolean isGmxLpEntrySettlement(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                && "GMX".equalsIgnoreCase(transaction.getProtocolName())
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank()
                && supportsGmxLpEntrySettlementPattern(transaction);
    }

    public void applyRequest(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());

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
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }

            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() >= 0) {
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

            CarryTransfer carry = flowSupport.removeFromPosition(flow, position);
            String assetIdentity = assetKey.assetIdentity();
            if (isGmxExecutionFeeReserveFlow(transaction, flow)) {
                bucket.addExecutionFeeReserve(assetIdentity, carry);
            } else {
                bucket.add(assetIdentity, carry);
            }
            replayState.ledgerPointCollector().record(
                    transaction,
                    flow,
                    indexedFlow.index(),
                    position.assetKey(),
                    before,
                    position,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
            );
        }
    }

    public void applySettlement(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        var bucket = replayState.asyncLifecycleBucket(transaction.getCorrelationId());
        List<IndexedFlow> shareInflows = new ArrayList<>();

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
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.GAS_ONLY
                );
                continue;
            }

            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
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

            if (isGmxShareLikeSymbol(flow.getAssetSymbol())) {
                shareInflows.add(indexedFlow);
                continue;
            }

            CarryTransfer refundCarry = bucket.takeExecutionFeeReserve(
                    assetKey.assetIdentity(),
                    flow.getQuantityDelta().abs(),
                    position.assetKey()
            );
            if (refundCarry != null) {
                flowSupport.restoreToPosition(refundCarry, position);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(refundCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    PositionSnapshot residualBefore = flowSupport.snapshot(position);
                    NormalizedTransaction.Flow residualFlow = flowSupport.copyFlowWithQuantity(flow, residualQuantity);
                    flowSupport.applyUnknownTransfer(residualFlow, position);
                    replayState.ledgerPointCollector().record(
                            transaction,
                            residualFlow,
                            indexedFlow.index(),
                            position.assetKey(),
                            residualBefore,
                            position,
                            AssetLedgerPoint.BasisEffect.UNKNOWN
                    );
                }
                continue;
            }

            CarryTransfer principalCarry = bucket.takeSameAssetCarry(
                    assetKey.assetIdentity(),
                    flow.getQuantityDelta().abs(),
                    position.assetKey()
            );
            if (principalCarry != null) {
                flowSupport.restoreToPosition(principalCarry, position);
                replayState.ledgerPointCollector().record(
                        transaction,
                        flow,
                        indexedFlow.index(),
                        position.assetKey(),
                        before,
                        position,
                        AssetLedgerPoint.BasisEffect.REALLOCATE_IN
                );
                BigDecimal residualQuantity = flow.getQuantityDelta().abs().subtract(principalCarry.quantity(), MC);
                if (residualQuantity.signum() > 0) {
                    PositionSnapshot residualBefore = flowSupport.snapshot(position);
                    NormalizedTransaction.Flow residualFlow = flowSupport.copyFlowWithQuantity(flow, residualQuantity);
                    flowSupport.applyUnknownTransfer(residualFlow, position);
                    replayState.ledgerPointCollector().record(
                            transaction,
                            residualFlow,
                            indexedFlow.index(),
                            position.assetKey(),
                            residualBefore,
                            position,
                            AssetLedgerPoint.BasisEffect.UNKNOWN
                    );
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

        if (!shareInflows.isEmpty()) {
            BigDecimal remainingPrincipalCostBasis = bucket.remainingCostBasisUsd();
            BigDecimal remainingExecutionFeeCostBasis = bucket.remainingExecutionFeeReserveCostBasisUsd();
            BigDecimal totalAllocatedCostBasis = remainingPrincipalCostBasis.add(remainingExecutionFeeCostBasis, MC);
            // ADR-040 net-carry conservation: the GLV/GM receipt must be minted carrying the SUM of the
            // contributed assets' Net (reward-discounted) basis — never re-seeded to the tax lane. The
            // Net remainder is transported from the same bucket carries that supply the tax remainder,
            // preserving each contributed asset's reward discount on the receipt (net ≤ tax).
            BigDecimal remainingPrincipalNetCostBasis = bucket.remainingNetCostBasisUsd();
            BigDecimal remainingExecutionFeeNetCostBasis = bucket.remainingExecutionFeeReserveNetCostBasisUsd();
            BigDecimal totalAllocatedNetCostBasis =
                    remainingPrincipalNetCostBasis.add(remainingExecutionFeeNetCostBasis, MC);
            if (remainingPrincipalCostBasis.signum() > 0 && bucket.remainingUncoveredQuantity().signum() <= 0) {
                if (shareInflows.size() == 1) {
                    restoreShareSettlementWithNet(
                            transaction,
                            shareInflows.getFirst(),
                            replayState,
                            totalAllocatedCostBasis,
                            totalAllocatedNetCostBasis
                    );
                } else if (assetSupport.allHaveKnownPrices(shareInflows.stream().map(IndexedFlow::flow).toList())) {
                    allocateShareSettlementByKnownValueWithNet(
                            transaction,
                            shareInflows,
                            replayState,
                            totalAllocatedCostBasis,
                            totalAllocatedNetCostBasis
                    );
                } else if (assetSupport.allSameAsset(shareInflows.stream().map(IndexedFlow::flow).toList(), transaction)) {
                    allocateShareSettlementByQuantityWithNet(
                            transaction,
                            shareInflows,
                            replayState,
                            totalAllocatedCostBasis,
                            totalAllocatedNetCostBasis
                    );
                } else {
                    applyUnknownGmxShareInflows(transaction, shareInflows, replayState);
                }
            } else {
                applyUnknownGmxShareInflows(transaction, shareInflows, replayState);
            }
        }

        bucket.clearAll();
        replayState.removeAsyncLifecycleBucket(transaction.getCorrelationId());
    }

    private void applyUnknownGmxShareInflows(
            NormalizedTransaction transaction,
            List<IndexedFlow> shareInflows,
            ReplayExecutionState replayState
    ) {
        for (IndexedFlow indexedFlow : shareInflows) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
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

    /**
     * ADR-040 net-carry conservation: restores a single settlement-share (GLV/GM receipt) inflow
     * carrying BOTH lanes independently — tax = {@code allocatedCost}, net = {@code allocatedNetCost}
     * (Σ contributed net basis). This replaces the tax-only {@code ReplaySettlementAllocator} restore
     * that re-seeded net = tax and erased the reward discount on the minted receipt.
     */
    private void restoreShareSettlementWithNet(
            NormalizedTransaction transaction,
            IndexedFlow indexedFlow,
            ReplayExecutionState replayState,
            BigDecimal allocatedCost,
            BigDecimal allocatedNetCost
    ) {
        NormalizedTransaction.Flow flow = indexedFlow.flow();
        AssetKey assetKey = assetSupport.assetKey(transaction, flow);
        PositionState position = replayState.position(assetKey);
        PositionSnapshot before = flowSupport.snapshot(position);
        BigDecimal quantity = flow.getQuantityDelta().abs();
        BigDecimal avco = safeDivide(allocatedCost, quantity);
        BigDecimal netCost = clampNetToTax(allocatedNetCost, allocatedCost);
        flowSupport.restoreToPosition(quantity, position, allocatedCost, netCost, BigDecimal.ZERO, avco);
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

    /**
     * ADR-040: net-aware peer of {@code allocateIndexedSettlementByKnownValue}. Splits BOTH the tax
     * and net totals across the share inflows by the SAME market-value weights, so per-leg
     * {@code net ≤ tax} is preserved and no net basis is fabricated.
     */
    private void allocateShareSettlementByKnownValueWithNet(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            ReplayExecutionState replayState,
            BigDecimal totalCost,
            BigDecimal totalNetCost
    ) {
        BigDecimal totalValue = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalValue = totalValue.add(
                    indexedFlow.flow().getQuantityDelta().abs().multiply(indexedFlow.flow().getUnitPriceUsd(), MC),
                    MC
            );
        }
        BigDecimal remainingCost = totalCost;
        BigDecimal remainingNetCost = totalNetCost;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            boolean last = index == flows.size() - 1;
            BigDecimal value = indexedFlow.flow().getQuantityDelta().abs().multiply(indexedFlow.flow().getUnitPriceUsd(), MC);
            BigDecimal weight = totalValue.signum() == 0 ? BigDecimal.ZERO : value.divide(totalValue, MC);
            BigDecimal allocatedCost = last ? remainingCost : totalCost.multiply(weight, MC);
            BigDecimal allocatedNetCost = last ? remainingNetCost : totalNetCost.multiply(weight, MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingNetCost = remainingNetCost.subtract(allocatedNetCost, MC);
            restoreShareSettlementWithNet(transaction, indexedFlow, replayState, allocatedCost, allocatedNetCost);
        }
    }

    /**
     * ADR-040: net-aware peer of {@code allocateIndexedSettlementByQuantity}. Splits BOTH lanes across
     * the share inflows by the SAME quantity weights (used for all-same-asset settlements).
     */
    private void allocateShareSettlementByQuantityWithNet(
            NormalizedTransaction transaction,
            List<IndexedFlow> flows,
            ReplayExecutionState replayState,
            BigDecimal totalCost,
            BigDecimal totalNetCost
    ) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow indexedFlow : flows) {
            totalQuantity = totalQuantity.add(indexedFlow.flow().getQuantityDelta().abs(), MC);
        }
        BigDecimal remainingCost = totalCost;
        BigDecimal remainingNetCost = totalNetCost;
        for (int index = 0; index < flows.size(); index++) {
            IndexedFlow indexedFlow = flows.get(index);
            boolean last = index == flows.size() - 1;
            BigDecimal quantity = indexedFlow.flow().getQuantityDelta().abs();
            BigDecimal weight = totalQuantity.signum() == 0 ? BigDecimal.ZERO : quantity.divide(totalQuantity, MC);
            BigDecimal allocatedCost = last ? remainingCost : totalCost.multiply(weight, MC);
            BigDecimal allocatedNetCost = last ? remainingNetCost : totalNetCost.multiply(weight, MC);
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            remainingNetCost = remainingNetCost.subtract(allocatedNetCost, MC);
            restoreShareSettlementWithNet(transaction, indexedFlow, replayState, allocatedCost, allocatedNetCost);
        }
    }

    /** Enforces the ADR-040 global gate {@code 0 ≤ net ≤ tax} on the receipt net lane. */
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

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private boolean supportsGmxLpEntryRequestPattern(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean hasPrincipalOutbound = false;
        boolean hasNativeExecutionReserve = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() >= 0) {
                return false;
            }
            if (isGmxExecutionFeeReserveFlow(transaction, flow)) {
                hasNativeExecutionReserve = true;
            } else {
                hasPrincipalOutbound = true;
            }
        }
        return hasPrincipalOutbound && hasNativeExecutionReserve;
    }

    private boolean supportsGmxLpEntrySettlementPattern(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean hasShareInflows = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER || flow.getQuantityDelta().signum() <= 0) {
                return false;
            }
            if (isGmxShareLikeSymbol(flow.getAssetSymbol())) {
                hasShareInflows = true;
            }
        }
        return hasShareInflows;
    }

    private boolean isGmxExecutionFeeReserveFlow(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() >= 0) {
            return false;
        }
        if (flow.getRole() != NormalizedLegRole.TRANSFER) {
            return false;
        }
        String assetIdentity = assetSupport.assetKey(transaction, flow).assetIdentity();
        if (assetIdentity == null || !assetIdentity.startsWith("NATIVE:")) {
            return false;
        }
        return transaction.getFlows().stream()
                .filter(candidate -> candidate != null
                        && candidate.getRole() == NormalizedLegRole.TRANSFER
                        && candidate.getQuantityDelta() != null
                        && candidate.getQuantityDelta().signum() < 0)
                .map(candidate -> assetSupport.assetKey(transaction, candidate).assetIdentity())
                .filter(Objects::nonNull)
                .anyMatch(candidateIdentity -> !candidateIdentity.equals(assetIdentity));
    }

    private boolean isGmxShareLikeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return symbol.regionMatches(true, 0, "GM:", 0, 3)
                || symbol.regionMatches(true, 0, "GLV", 0, 3);
    }
}
