package com.walletradar.application.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.math.MathContext;

public final class ContinuityBucket {

    private static final MathContext MC = MathContext.DECIMAL128;

    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
    private BigDecimal netTotalCostBasisUsd = BigDecimal.ZERO;
    private BigDecimal uncoveredQuantity = BigDecimal.ZERO;

    public void add(CarryTransfer carry) {
        quantity = quantity.add(carry.quantity());
        totalCostBasisUsd = totalCostBasisUsd.add(carry.costBasisUsd());
        netTotalCostBasisUsd = netTotalCostBasisUsd.add(carry.netCostBasisUsd());
        uncoveredQuantity = uncoveredQuantity.add(carry.uncoveredQuantity());
    }

    public CarryTransfer take(BigDecimal requestedQuantity, AssetKey assetKey) {
        BigDecimal availableQuantity = quantity;
        BigDecimal availableUncovered = uncoveredQuantity;
        BigDecimal availableCovered = nonNegative(availableQuantity.subtract(availableUncovered, MC));
        BigDecimal appliedQuantity = requestedQuantity.min(availableQuantity);
        BigDecimal coveredQuantity = appliedQuantity.min(availableCovered);
        BigDecimal uncoveredQuantityToApply = nonNegative(requestedQuantity.subtract(coveredQuantity, MC));
        BigDecimal avco = availableCovered.signum() <= 0
                ? null
                : safeDivide(totalCostBasisUsd, availableCovered);
        BigDecimal netAvco = availableCovered.signum() <= 0
                ? null
                : safeDivide(netTotalCostBasisUsd, availableCovered);
        BigDecimal cost = avco == null
                ? BigDecimal.ZERO
                : coveredQuantity.multiply(avco, MC);
        BigDecimal netCost = netAvco == null
                ? BigDecimal.ZERO
                : coveredQuantity.multiply(netAvco, MC);
        quantity = nonNegative(quantity.subtract(appliedQuantity, MC));
        totalCostBasisUsd = nonNegative(totalCostBasisUsd.subtract(cost, MC));
        netTotalCostBasisUsd = nonNegative(netTotalCostBasisUsd.subtract(netCost, MC));
        uncoveredQuantity = nonNegative(availableUncovered.subtract(nonNegative(appliedQuantity.subtract(coveredQuantity, MC)), MC));
        return new CarryTransfer(
                appliedQuantity,
                coveredQuantity,
                uncoveredQuantityToApply,
                cost,
                avco,
                netCost,
                netAvco,
                false,
                assetKey
        );
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public BigDecimal totalQuantity() {
        return quantity;
    }

    /**
     * Drains the entire bucket as a single carry. Delegates to {@code take(totalQty, assetKey)}
     * so that cost basis and avco are computed via the standard weighted-average logic.
     * Used for wrapper-composite buckets (VAULT_WITHDRAW, STAKING_WITHDRAW) where the receipt
     * token and the returned asset have incompatible quantity scales.
     */
    public CarryTransfer drainAll(AssetKey assetKey) {
        BigDecimal total = totalQuantity();
        if (total == null || total.signum() <= 0) {
            return null;
        }
        return take(total, assetKey);
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
