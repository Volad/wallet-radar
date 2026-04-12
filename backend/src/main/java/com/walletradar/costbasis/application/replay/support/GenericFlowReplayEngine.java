package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.QuantityConsumption;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

@Component
public class GenericFlowReplayEngine {

    private static final MathContext MC = MathContext.DECIMAL128;

    public void applyBuy(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        if (hasKnownPrice(flow)) {
            BigDecimal acquisitionCost = quantity.multiply(flow.getUnitPriceUsd(), MC);
            position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(acquisitionCost));
            position.setQuantity(position.quantity().add(quantity));
            recomputePerWalletAvco(position);
            return;
        }
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(quantity));
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    public void applySell(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfSale = position.perWalletAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal soldCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avcoAtTimeOfSale != null && soldCoveredQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(avcoAtTimeOfSale);
            BigDecimal relievedCost = soldCoveredQuantity.multiply(avcoAtTimeOfSale, MC);
            position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(relievedCost, MC)));
            if (hasKnownPrice(flow)
                    && consumption.externalShortfallQuantity().signum() == 0
                    && consumption.uncoveredQuantity().signum() == 0) {
                BigDecimal realised = flow.getUnitPriceUsd().subtract(avcoAtTimeOfSale, MC).multiply(soldCoveredQuantity, MC);
                flow.setRealisedPnlUsd(realised);
                position.setTotalRealisedPnlUsd(position.totalRealisedPnlUsd().add(realised));
            } else {
                flow.setAvcoAtTimeOfSale(null);
                flow.setRealisedPnlUsd(null);
                markUnresolved(position);
            }
        } else if (requestedQuantity.signum() > 0) {
            flow.setAvcoAtTimeOfSale(null);
            flow.setRealisedPnlUsd(null);
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    public void applyFee(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avcoAtTimeOfCharge = position.perWalletAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal chargedCoveredQuantity = consumption.coveredQuantity();
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (hasKnownPrice(flow)) {
            BigDecimal feeCost = requestedQuantity.multiply(flow.getUnitPriceUsd(), MC);
            position.setTotalGasPaidUsd(position.totalGasPaidUsd().add(feeCost));
            if (avcoAtTimeOfCharge != null && chargedCoveredQuantity.signum() > 0) {
                position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(
                        chargedCoveredQuantity.multiply(avcoAtTimeOfCharge, MC),
                        MC
                )));
            }
        } else {
            markUnresolved(position);
        }
        if (consumption.uncoveredQuantity().signum() > 0 || consumption.externalShortfallQuantity().signum() > 0) {
            markUnresolved(position);
        }
        recomputePerWalletAvco(position);
    }

    public void applyUnknownTransfer(NormalizedTransaction.Flow flow, PositionState position) {
        if (flow.getQuantityDelta().signum() > 0) {
            position.setQuantity(position.quantity().add(flow.getQuantityDelta().abs()));
            position.setUncoveredQuantity(position.uncoveredQuantity().add(flow.getQuantityDelta().abs()));
            markUnresolved(position);
            recomputePerWalletAvco(position);
            return;
        }
        QuantityConsumption consumption = consumeQuantity(position, flow.getQuantityDelta().abs());
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    public void applySponsoredGasIn(NormalizedTransaction.Flow flow, PositionState position) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public void materializePendingInbound(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal quantity = flow.getQuantityDelta().abs();
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(quantity));
        markUnresolved(position);
        recomputePerWalletAvco(position);
    }

    public CarryTransfer removeFromPosition(NormalizedTransaction.Flow flow, PositionState position) {
        BigDecimal requestedQuantity = flow.getQuantityDelta().abs();
        BigDecimal avco = position.perWalletAvco();
        QuantityConsumption consumption = consumeQuantity(position, requestedQuantity);
        BigDecimal cost = avco == null
                ? BigDecimal.ZERO
                : consumption.coveredQuantity().multiply(avco, MC);
        position.setTotalCostBasisUsd(nonNegative(position.totalCostBasisUsd().subtract(cost, MC)));
        recomputePerWalletAvco(position);
        if (consumption.externalShortfallQuantity().signum() > 0) {
            recordQuantityShortfall(position, consumption.externalShortfallQuantity());
        }
        if (avco == null && consumption.coveredQuantity().signum() > 0) {
            markUnresolved(position);
        }
        return new CarryTransfer(
                requestedQuantity,
                consumption.coveredQuantity(),
                requestedQuantity.subtract(consumption.coveredQuantity(), MC),
                cost,
                avco,
                false,
                position.assetKey()
        );
    }

    public void restoreToPosition(
            NormalizedTransaction.Flow flow,
            PositionState position,
            BigDecimal avco,
            BigDecimal cost
    ) {
        restoreToPosition(flow.getQuantityDelta().abs(), position, cost, BigDecimal.ZERO, avco);
    }

    public void restoreToPosition(
            BigDecimal quantity,
            PositionState position,
            BigDecimal cost,
            BigDecimal uncoveredQuantity,
            BigDecimal avco
    ) {
        position.setQuantity(position.quantity().add(quantity));
        position.setUncoveredQuantity(position.uncoveredQuantity().add(uncoveredQuantity));
        position.setTotalCostBasisUsd(position.totalCostBasisUsd().add(cost));
        recomputePerWalletAvco(position);
        if (uncoveredQuantity.signum() > 0 || avco == null) {
            markUnresolved(position);
        }
    }

    public void markUnresolved(PositionState position) {
        position.setHasIncompleteHistory(true);
        position.setHasUnresolvedFlags(true);
        position.setUnresolvedFlagCount(position.unresolvedFlagCount() + 1);
    }

    public void recordQuantityShortfall(PositionState position, BigDecimal quantityShortfall) {
        if (quantityShortfall == null || quantityShortfall.signum() <= 0) {
            return;
        }
        position.setQuantityShortfall(position.quantityShortfall().add(quantityShortfall));
        markUnresolved(position);
    }

    public QuantityConsumption consumeQuantity(PositionState position, BigDecimal requestedQuantity) {
        BigDecimal availableQuantity = position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        BigDecimal availableUncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal availableCovered = nonNegative(availableQuantity.subtract(availableUncovered, MC));
        BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
        BigDecimal coveredQuantity = appliedQuantity.min(availableCovered);
        BigDecimal uncoveredQuantity = nonNegative(appliedQuantity.subtract(coveredQuantity, MC));
        BigDecimal externalShortfallQuantity = nonNegative(requestedQuantity.subtract(appliedQuantity, MC));
        position.setQuantity(nonNegative(availableQuantity.subtract(appliedQuantity, MC)));
        position.setUncoveredQuantity(nonNegative(availableUncovered.subtract(uncoveredQuantity, MC)));
        return new QuantityConsumption(appliedQuantity, coveredQuantity, uncoveredQuantity, externalShortfallQuantity);
    }

    public void recomputePerWalletAvco(PositionState position) {
        BigDecimal coveredQuantity = nonNegative(position.quantity().subtract(position.uncoveredQuantity(), MC));
        position.setPerWalletAvco(coveredQuantity.signum() == 0
                ? null
                : safeDivide(position.totalCostBasisUsd(), coveredQuantity));
    }

    public void resolveTemporaryUnresolved(PositionState position) {
        if (position.unresolvedFlagCount() > 0) {
            position.setUnresolvedFlagCount(position.unresolvedFlagCount() - 1);
        }
        if (position.unresolvedFlagCount() == 0) {
            position.setHasIncompleteHistory(false);
            position.setHasUnresolvedFlags(false);
        }
    }

    private static boolean hasKnownPrice(NormalizedTransaction.Flow flow) {
        return flow.getUnitPriceUsd() != null
                && flow.getPriceSource() != null
                && flow.getPriceSource() != PriceSource.UNKNOWN;
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
