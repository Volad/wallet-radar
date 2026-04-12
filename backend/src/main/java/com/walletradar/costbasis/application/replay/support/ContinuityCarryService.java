package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.costbasis.application.replay.model.FlowRef;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridor;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

@Component
public class ContinuityCarryService {

    private static final MathContext MC = MathContext.DECIMAL128;

    private final GenericFlowReplayEngine genericFlowReplayEngine;
    private final ReplayFlowSupport flowSupport;

    public ContinuityCarryService(
            GenericFlowReplayEngine genericFlowReplayEngine,
            ReplayFlowSupport flowSupport
    ) {
        this.genericFlowReplayEngine = genericFlowReplayEngine;
        this.flowSupport = flowSupport;
    }

    public ContinuityBucket continuityBucket(
            Map<ContinuityKey, ContinuityBucket> continuityBuckets,
            ContinuityKey continuityKey
    ) {
        return continuityBuckets.computeIfAbsent(continuityKey, ignored -> new ContinuityBucket());
    }

    public void moveToBucket(CarryTransfer carry, ContinuityBucket bucket) {
        if (carry == null || bucket == null) {
            return;
        }
        bucket.add(carry);
    }

    public CarryTransfer takeFromBucket(
            ContinuityBucket bucket,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (bucket == null || bucket.quantity().signum() == 0) {
            return null;
        }
        return bucket.take(requestedQuantity, assetKey);
    }

    public CarryTransfer takeReservedCarry(
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries,
            FlowRef flowRef,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (reservedPassThroughCarries == null || flowRef == null) {
            return null;
        }
        CarryTransfer reservedCarry = reservedPassThroughCarries.remove(flowRef);
        if (reservedCarry == null) {
            return null;
        }
        return sliceCarryTransfer(reservedCarry, requestedQuantity, assetKey);
    }

    public void reservePassThroughCarry(
            PassThroughCorridorPlan passThroughCorridorPlan,
            FlowRef inboundFlowRef,
            CarryTransfer carry,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        if (passThroughCorridorPlan == null
                || inboundFlowRef == null
                || reservedPassThroughCarries == null
                || carry == null
                || carry.quantity() == null
                || carry.quantity().signum() <= 0) {
            return;
        }
        PassThroughCorridor corridor = passThroughCorridorPlan.byInboundFlowRef().get(inboundFlowRef);
        if (corridor == null) {
            return;
        }
        reservedPassThroughCarries.put(
                corridor.outboundFlowRef(),
                sliceCarryTransfer(carry, corridor.reservedQuantity(), carry.assetKey())
        );
    }

    public CarryTransfer removeTransferCarry(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow,
            int flowIndex,
            PositionState position,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        FlowRef flowRef = flowSupport.flowRef(transaction, flowIndex);
        CarryTransfer reservedPortion = takeReservedCarry(
                reservedPassThroughCarries,
                flowRef,
                flow.getQuantityDelta().abs(),
                position.assetKey()
        );
        if (reservedPortion == null) {
            return genericFlowReplayEngine.removeFromPosition(flow, position);
        }
        consumeReservedCarry(position, reservedPortion);
        BigDecimal remainingQuantity = nonNegative(flow.getQuantityDelta().abs().subtract(reservedPortion.quantity(), MC));
        if (remainingQuantity.signum() == 0) {
            return reservedPortion;
        }
        CarryTransfer pooledRemainder = genericFlowReplayEngine.removeFromPosition(
                flowSupport.copyFlowWithQuantity(
                        flow,
                        flow.getQuantityDelta().signum() < 0 ? remainingQuantity.negate() : remainingQuantity
                ),
                position
        );
        return mergeCarryTransfers(position.assetKey(), reservedPortion, pooledRemainder);
    }

    public void reservePassThroughCarryIfPlanned(
            NormalizedTransaction transaction,
            int flowIndex,
            CarryTransfer carry,
            PassThroughCorridorPlan passThroughCorridorPlan,
            Map<FlowRef, CarryTransfer> reservedPassThroughCarries
    ) {
        reservePassThroughCarry(
                passThroughCorridorPlan,
                flowSupport.flowRef(transaction, flowIndex),
                carry,
                reservedPassThroughCarries
        );
    }

    public void consumeReservedCarry(PositionState position, CarryTransfer carry) {
        if (position == null || carry == null || carry.quantity() == null || carry.quantity().signum() <= 0) {
            return;
        }
        position.setQuantity(nonNegative(position.quantity().subtract(carry.quantity(), MC)));
        position.setUncoveredQuantity(nonNegative(position.uncoveredQuantity().subtract(carry.uncoveredQuantity(), MC)));
        position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(carry.costBasisUsd(), MC)));
        genericFlowReplayEngine.recomputePerWalletAvco(position);
    }

    public CarryTransfer sliceCarryTransfer(
            CarryTransfer carry,
            BigDecimal requestedQuantity,
            AssetKey assetKey
    ) {
        if (carry == null || requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal effectiveQuantity = requestedQuantity.min(carry.quantity());
        BigDecimal effectiveCoveredQuantity = effectiveQuantity.min(carry.coveredQuantity());
        BigDecimal effectiveUncoveredQuantity = nonNegative(effectiveQuantity.subtract(effectiveCoveredQuantity, MC));
        BigDecimal effectiveAvco = carry.avco();
        BigDecimal effectiveCost = effectiveAvco == null
                ? BigDecimal.ZERO
                : effectiveCoveredQuantity.multiply(effectiveAvco, MC);
        return new CarryTransfer(
                effectiveQuantity,
                effectiveCoveredQuantity,
                effectiveUncoveredQuantity,
                effectiveCost,
                effectiveAvco,
                false,
                assetKey
        );
    }

    public CarryTransfer bridgeInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal coveredQuantity = destinationQuantity.min(sourceCarry.coveredQuantity());
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd();
        BigDecimal avco = coveredQuantity.signum() <= 0
                ? null
                : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity,
                coveredQuantity,
                uncoveredQuantity,
                costBasisUsd,
                avco,
                false,
                assetKey
        );
    }

    public CarryTransfer bridgeSettlementInboundCarry(
            CarryTransfer sourceCarry,
            BigDecimal destinationQuantity,
            AssetKey assetKey
    ) {
        if (sourceCarry == null || destinationQuantity == null || destinationQuantity.signum() <= 0) {
            return emptyCarry(assetKey);
        }
        BigDecimal sourceQuantity = sourceCarry.quantity();
        if (sourceQuantity == null || sourceQuantity.signum() <= 0) {
            return new CarryTransfer(
                    destinationQuantity,
                    BigDecimal.ZERO,
                    destinationQuantity,
                    BigDecimal.ZERO,
                    null,
                    false,
                    assetKey
            );
        }
        BigDecimal sourceCoveredQuantity = sourceCarry.coveredQuantity().min(sourceQuantity);
        BigDecimal coverageRatio = safeDivide(sourceCoveredQuantity, sourceQuantity);
        BigDecimal coveredQuantity = coverageRatio == null
                ? BigDecimal.ZERO
                : destinationQuantity.multiply(coverageRatio, MC).min(destinationQuantity);
        BigDecimal uncoveredQuantity = nonNegative(destinationQuantity.subtract(coveredQuantity, MC));
        BigDecimal costBasisUsd = sourceCarry.costBasisUsd();
        BigDecimal avco = coveredQuantity.signum() <= 0
                ? null
                : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(
                destinationQuantity,
                coveredQuantity,
                uncoveredQuantity,
                costBasisUsd,
                avco,
                false,
                assetKey
        );
    }

    public CarryTransfer mergeCarryTransfers(
            AssetKey assetKey,
            CarryTransfer left,
            CarryTransfer right
    ) {
        BigDecimal quantity = safeAdd(left == null ? null : left.quantity(), right == null ? null : right.quantity());
        BigDecimal coveredQuantity = safeAdd(left == null ? null : left.coveredQuantity(), right == null ? null : right.coveredQuantity());
        BigDecimal uncoveredQuantity = safeAdd(left == null ? null : left.uncoveredQuantity(), right == null ? null : right.uncoveredQuantity());
        BigDecimal costBasisUsd = safeAdd(left == null ? null : left.costBasisUsd(), right == null ? null : right.costBasisUsd());
        BigDecimal avco = coveredQuantity.signum() <= 0 ? null : safeDivide(costBasisUsd, coveredQuantity);
        return new CarryTransfer(quantity, coveredQuantity, uncoveredQuantity, costBasisUsd, avco, false, assetKey);
    }

    private CarryTransfer emptyCarry(AssetKey assetKey) {
        return new CarryTransfer(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, false, assetKey);
    }

    private BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left).add(right == null ? BigDecimal.ZERO : right, MC);
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
