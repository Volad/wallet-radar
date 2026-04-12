package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

@Component
public class EulerLoopReplayHandler {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final ReplayAssetSupport assetSupport;
    private final ReplayFlowSupport flowSupport;

    public EulerLoopReplayHandler(
            ReplayAssetSupport assetSupport,
            ReplayFlowSupport flowSupport
    ) {
        this.assetSupport = assetSupport;
        this.flowSupport = flowSupport;
    }

    public void apply(NormalizedTransaction transaction, ReplayExecutionState replayState) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return;
        }

        NormalizedTransaction.Flow sourceFlow = null;
        NormalizedTransaction.Flow replacementFlow = null;
        List<NormalizedTransaction.Flow> sameAssetRefunds = new ArrayList<>();
        List<NormalizedTransaction.Flow> fees = new ArrayList<>();

        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                fees.add(flow);
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                applyUnknown(transaction, flow, replayState);
                continue;
            }
            if (flow.getQuantityDelta().signum() < 0) {
                sourceFlow = flow;
                continue;
            }
            if (sourceFlow != null && assetSupport.sameAssetIdentity(transaction, sourceFlow, flow)) {
                sameAssetRefunds.add(flow);
                continue;
            }
            replacementFlow = flow;
        }

        if (sourceFlow == null || replacementFlow == null) {
            for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
                applyUnknown(transaction, flow, replayState);
            }
            return;
        }

        AssetKey sourceAssetKey = assetSupport.assetKey(transaction, sourceFlow);
        PositionState sourcePosition = replayState.position(sourceAssetKey);
        sourcePosition.setLastEventTimestamp(flowSupport.laterOf(sourcePosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot sourceBefore = flowSupport.snapshot(sourcePosition);
        CarryTransfer carry = flowSupport.removeFromPosition(sourceFlow, sourcePosition);
        replayState.ledgerPointCollector().record(
                transaction,
                sourceFlow,
                flowSupport.flowIndex(transaction, sourceFlow),
                sourcePosition.assetKey(),
                sourceBefore,
                sourcePosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
        );

        CarryTransfer remainingCarry = carry;
        for (NormalizedTransaction.Flow refund : sameAssetRefunds) {
            sourcePosition.setLastEventTimestamp(flowSupport.laterOf(sourcePosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot refundBefore = flowSupport.snapshot(sourcePosition);
            remainingCarry = restoreRefund(refund, sourcePosition, remainingCarry);
            replayState.ledgerPointCollector().record(
                    transaction,
                    refund,
                    flowSupport.flowIndex(transaction, refund),
                    sourcePosition.assetKey(),
                    refundBefore,
                    sourcePosition,
                    AssetLedgerPoint.BasisEffect.REALLOCATE_IN
            );
        }

        AssetKey replacementAssetKey = assetSupport.assetKey(transaction, replacementFlow);
        PositionState replacementPosition = replayState.position(replacementAssetKey);
        replacementPosition.setLastEventTimestamp(flowSupport.laterOf(replacementPosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
        PositionSnapshot replacementBefore = flowSupport.snapshot(replacementPosition);
        restoreReplacement(replacementFlow, replacementPosition, remainingCarry);
        replayState.ledgerPointCollector().record(
                transaction,
                replacementFlow,
                flowSupport.flowIndex(transaction, replacementFlow),
                replacementPosition.assetKey(),
                replacementBefore,
                replacementPosition,
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN
        );

        for (NormalizedTransaction.Flow fee : fees) {
            AssetKey feeAssetKey = assetSupport.assetKey(transaction, fee);
            PositionState feePosition = replayState.position(feeAssetKey);
            feePosition.setLastEventTimestamp(flowSupport.laterOf(feePosition.lastEventTimestamp(), transaction.getBlockTimestamp()));
            PositionSnapshot feeBefore = flowSupport.snapshot(feePosition);
            flowSupport.applyFee(fee, feePosition);
            replayState.ledgerPointCollector().record(
                    transaction,
                    fee,
                    flowSupport.flowIndex(transaction, fee),
                    feePosition.assetKey(),
                    feeBefore,
                    feePosition,
                    AssetLedgerPoint.BasisEffect.GAS_ONLY
            );
        }
    }

    private void applyUnknown(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            ReplayExecutionState replayState
    ) {
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
            return;
        }
        flowSupport.applyUnknownTransfer(flow, position);
        replayState.ledgerPointCollector().record(
                transaction,
                flow,
                flowSupport.flowIndex(transaction, flow),
                position.assetKey(),
                before,
                position,
                AssetLedgerPoint.BasisEffect.UNKNOWN
        );
    }

    private CarryTransfer restoreRefund(
            NormalizedTransaction.Flow refundFlow,
            PositionState position,
            CarryTransfer carry
    ) {
        if (carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            flowSupport.applyUnknownTransfer(refundFlow, position);
            return carry;
        }
        BigDecimal refundQuantity = refundFlow.getQuantityDelta().abs();
        BigDecimal effectiveQuantity = refundQuantity.min(carry.quantity());
        if (effectiveQuantity.signum() <= 0) {
            return carry;
        }
        BigDecimal coveredRefundQuantity = effectiveQuantity.min(carry.coveredQuantity());
        BigDecimal uncoveredRefundQuantity = nonNegative(effectiveQuantity.subtract(coveredRefundQuantity, MC));
        BigDecimal avco = carry.avco();
        BigDecimal refundCost = avco == null ? BigDecimal.ZERO : coveredRefundQuantity.multiply(avco, MC);
        flowSupport.restoreToPosition(effectiveQuantity, position, refundCost, uncoveredRefundQuantity, avco);
        return new CarryTransfer(
                nonNegative(carry.quantity().subtract(effectiveQuantity, MC)),
                nonNegative(carry.coveredQuantity().subtract(coveredRefundQuantity, MC)),
                nonNegative(carry.uncoveredQuantity().subtract(uncoveredRefundQuantity, MC)),
                nonNegative(carry.costBasisUsd().subtract(refundCost, MC)),
                avco,
                false,
                carry.assetKey()
        );
    }

    private void restoreReplacement(
            NormalizedTransaction.Flow replacementFlow,
            PositionState position,
            CarryTransfer carry
    ) {
        if (carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            flowSupport.applyUnknownTransfer(replacementFlow, position);
            return;
        }
        BigDecimal quantity = replacementFlow.getQuantityDelta().abs();
        BigDecimal coveredQuantity = quantity.min(carry.coveredQuantity());
        BigDecimal uncoveredQuantity = nonNegative(quantity.subtract(coveredQuantity, MC));
        BigDecimal avco = coveredQuantity.signum() == 0
                ? null
                : safeDivide(carry.costBasisUsd(), coveredQuantity);
        flowSupport.restoreToPosition(quantity, position, carry.costBasisUsd(), uncoveredQuantity, avco);
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
