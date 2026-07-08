package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AsyncSpotOrderBucket;
import com.walletradar.application.costbasis.application.replay.model.AsyncSpotOrderCarry;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

@Component
public class AsyncSpotOrderReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public AsyncSpotOrderReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
    }

    public boolean isAsyncSpotOrderRequestSell(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.SELL
                && flow.getQuantityDelta().signum() < 0
                && transaction.getType() == NormalizedTransactionType.DEX_ORDER_REQUEST
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    public boolean isAsyncSpotOrderSettlementBuy(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        return transaction != null
                && flow != null
                && flow.getRole() == NormalizedLegRole.BUY
                && flow.getQuantityDelta().signum() > 0
                && transaction.getType() == NormalizedTransactionType.DEX_ORDER_SETTLEMENT
                && transaction.getCorrelationId() != null
                && !transaction.getCorrelationId().isBlank();
    }

    public void applyRequest(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        CarryTransfer carry = flowSupport.removeFromPosition(flow, position);
        replayState.asyncSpotOrderBucket(transaction.getCorrelationId()).add(carry, flow);
    }

    public void applySettlement(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            PositionState position,
            ReplayExecutionState replayState
    ) {
        AsyncSpotOrderBucket bucket = replayState.findAsyncSpotOrderBucket(transaction.getCorrelationId());
        if (bucket == null || bucket.isEmpty() || !assetSupport.hasKnownPrice(flow)) {
            flowSupport.applyBuy(flow, position);
            return;
        }

        BigDecimal totalSourceQuantity = bucket.totalQuantity();
        BigDecimal settlementQuantity = flow.getQuantityDelta().abs();
        BigDecimal settlementValueUsd = settlementQuantity.multiply(flow.getUnitPriceUsd(), MC);
        flowSupport.restoreToPosition(flow, position, flow.getUnitPriceUsd(), settlementValueUsd);

        BigDecimal remainingProceeds = settlementValueUsd;
        BigDecimal remainingQuantity = totalSourceQuantity;
        List<AsyncSpotOrderCarry> carries = bucket.drainAll();
        for (int index = 0; index < carries.size(); index++) {
            AsyncSpotOrderCarry entry = carries.get(index);
            NormalizedTransaction.Flow requestFlow = entry.requestFlow();
            CarryTransfer carry = entry.carry();
            if (requestFlow == null || carry == null) {
                continue;
            }
            BigDecimal allocatedProceeds;
            if (index == carries.size() - 1 || remainingQuantity == null || remainingQuantity.signum() <= 0) {
                allocatedProceeds = remainingProceeds;
            } else {
                BigDecimal ratio = safeDivide(carry.quantity(), remainingQuantity);
                allocatedProceeds = ratio == null
                        ? BigDecimal.ZERO
                        : settlementValueUsd.multiply(ratio, MC);
                remainingProceeds = remainingProceeds.subtract(allocatedProceeds, MC);
                remainingQuantity = remainingQuantity.subtract(carry.quantity(), MC);
            }
            if (carry.avco() != null) {
                requestFlow.setAvcoAtTimeOfSale(carry.avco());
                requestFlow.setRealisedPnlUsd(allocatedProceeds.subtract(carry.costBasisUsd(), MC));
            } else {
                requestFlow.setAvcoAtTimeOfSale(null);
                requestFlow.setRealisedPnlUsd(null);
            }
        }
        replayState.removeAsyncSpotOrderBucket(transaction.getCorrelationId());
    }

    private static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }
}
