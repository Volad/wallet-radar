package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.LiquidStakingCarry;
import com.walletradar.costbasis.application.replay.model.LiquidStakingFlowSelection;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class LiquidStakingReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;
    private final ReplaySettlementAllocator settlementAllocator;

    public LiquidStakingReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport,
            ReplaySettlementAllocator settlementAllocator
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
        this.settlementAllocator = settlementAllocator;
    }

    public LiquidStakingFlowSelection selectPrincipalFlows(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getFlows() == null
                || transaction.getFlows().isEmpty()
                || (transaction.getType() != NormalizedTransactionType.STAKING_DEPOSIT
                && transaction.getType() != NormalizedTransactionType.STAKING_WITHDRAW)) {
            return LiquidStakingFlowSelection.empty();
        }

        Map<String, List<IndexedFlow>> flowsByFamily = new LinkedHashMap<>();
        for (IndexedFlow indexedFlow : flowSupport.indexedFlows(transaction)) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            if (flow == null
                    || flow.getRole() != NormalizedLegRole.TRANSFER
                    || flow.getQuantityDelta() == null
                    || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            String continuityIdentity = AccountingAssetFamilySupport.continuityIdentity(flow);
            if (continuityIdentity == null || !continuityIdentity.startsWith("FAMILY:")) {
                continue;
            }
            flowsByFamily.computeIfAbsent(continuityIdentity, ignored -> new ArrayList<>()).add(indexedFlow);
        }

        List<IndexedFlow> outbound = new ArrayList<>();
        List<IndexedFlow> inbound = new ArrayList<>();
        for (List<IndexedFlow> familyFlows : flowsByFamily.values()) {
            boolean hasOutbound = familyFlows.stream().anyMatch(flow -> flow.flow().getQuantityDelta().signum() < 0);
            boolean hasInbound = familyFlows.stream().anyMatch(flow -> flow.flow().getQuantityDelta().signum() > 0);
            long distinctAssets = familyFlows.stream()
                    .map(flow -> assetSupport.assetIdentity(transaction, flow.flow()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            if (!hasOutbound || !hasInbound || distinctAssets < 2) {
                continue;
            }
            for (IndexedFlow familyFlow : familyFlows) {
                if (familyFlow.flow().getQuantityDelta().signum() < 0) {
                    outbound.add(familyFlow);
                } else {
                    inbound.add(familyFlow);
                }
            }
        }
        if (outbound.isEmpty() || inbound.isEmpty()) {
            return LiquidStakingFlowSelection.empty();
        }
        outbound.sort(java.util.Comparator.comparingInt(IndexedFlow::index));
        inbound.sort(java.util.Comparator.comparingInt(IndexedFlow::index));
        return new LiquidStakingFlowSelection(outbound, inbound);
    }

    public void applySelected(
            NormalizedTransaction transaction,
            LiquidStakingFlowSelection selection,
            ReplayExecutionState replayState
    ) {
        LiquidStakingCarry carry = new LiquidStakingCarry();
        for (IndexedFlow indexedFlow : selection.outbound()) {
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot before = flowSupport.snapshot(position);
            CarryTransfer removedCarry = flowSupport.removeFromPosition(flow, position);
            carry.add(removedCarry);
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

        allocateInbound(transaction, selection.inbound(), replayState, carry);
    }

    private void allocateInbound(
            NormalizedTransaction transaction,
            List<IndexedFlow> inboundFlows,
            ReplayExecutionState replayState,
            LiquidStakingCarry carry
    ) {
        if (carry.totalSourceQuantity().signum() <= 0) {
            for (IndexedFlow indexedFlow : inboundFlows) {
                settlementAllocator.applyFallbackSettlementFlow(
                        transaction,
                        indexedFlow.flow(),
                        replayState.positions(),
                        replayState.ledgerPointCollector()
                );
            }
            return;
        }

        BigDecimal coveredRatio = safeDivide(carry.totalCoveredQuantity(), carry.totalSourceQuantity());
        if (coveredRatio == null) {
            coveredRatio = BigDecimal.ZERO;
        }
        if (coveredRatio.signum() < 0) {
            coveredRatio = BigDecimal.ZERO;
        }
        if (coveredRatio.compareTo(BigDecimal.ONE) > 0) {
            coveredRatio = BigDecimal.ONE;
        }

        BigDecimal totalInboundWeight = totalInboundWeight(inboundFlows);
        BigDecimal remainingCost = carry.totalCostBasisUsd();
        for (int index = 0; index < inboundFlows.size(); index++) {
            IndexedFlow indexedFlow = inboundFlows.get(index);
            NormalizedTransaction.Flow flow = indexedFlow.flow();
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);

            AssetKey assetKey = assetSupport.assetKey(transaction, flow);
            PositionState position = replayState.position(assetKey);
            position.setLastEventTimestamp(flowSupport.laterOf(position.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot before = flowSupport.snapshot(position);

            BigDecimal quantity = flow.getQuantityDelta().abs();
            BigDecimal allocatedCost = index == inboundFlows.size() - 1
                    ? remainingCost
                    : carry.totalCostBasisUsd().multiply(
                    inboundWeight(indexedFlow).divide(totalInboundWeight, MC),
                    MC
            );
            remainingCost = remainingCost.subtract(allocatedCost, MC);
            BigDecimal coveredQuantity = quantity.multiply(coveredRatio, MC);
            BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
            BigDecimal avco = coveredQuantity.signum() > 0
                    ? safeDivide(allocatedCost, coveredQuantity)
                    : null;
            flowSupport.restoreToPosition(quantity, position, allocatedCost, uncoveredQuantity, avco);
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
    }

    private BigDecimal totalInboundWeight(List<IndexedFlow> inboundFlows) {
        if (assetSupport.allHaveKnownPrices(inboundFlows.stream().map(IndexedFlow::flow).toList())) {
            BigDecimal total = BigDecimal.ZERO;
            for (IndexedFlow inboundFlow : inboundFlows) {
                total = total.add(inboundWeight(inboundFlow), MC);
            }
            if (total.signum() > 0) {
                return total;
            }
        }
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (IndexedFlow inboundFlow : inboundFlows) {
            totalQuantity = totalQuantity.add(inboundFlow.flow().getQuantityDelta().abs(), MC);
        }
        return totalQuantity.signum() > 0 ? totalQuantity : BigDecimal.ONE;
    }

    private BigDecimal inboundWeight(IndexedFlow inboundFlow) {
        NormalizedTransaction.Flow flow = inboundFlow.flow();
        if (assetSupport.hasKnownPrice(flow)) {
            BigDecimal value = flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
            if (value.signum() > 0) {
                return value;
            }
        }
        return flow.getQuantityDelta().abs();
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
