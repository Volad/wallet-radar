package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.math.MathContext;

public final class LiquidStakingCarry {

    private static final MathContext MC = MathContext.DECIMAL128;

    private BigDecimal totalSourceQuantity = BigDecimal.ZERO;
    private BigDecimal totalCoveredQuantity = BigDecimal.ZERO;
    private BigDecimal totalUncoveredQuantity = BigDecimal.ZERO;
    private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;

    public void add(CarryTransfer carry) {
        if (carry == null) {
            return;
        }
        totalSourceQuantity = totalSourceQuantity.add(carry.quantity(), MC);
        totalCoveredQuantity = totalCoveredQuantity.add(carry.coveredQuantity(), MC);
        totalUncoveredQuantity = totalUncoveredQuantity.add(carry.uncoveredQuantity(), MC);
        totalCostBasisUsd = totalCostBasisUsd.add(carry.costBasisUsd(), MC);
    }

    public BigDecimal totalSourceQuantity() {
        return totalSourceQuantity;
    }

    public BigDecimal totalCoveredQuantity() {
        return totalCoveredQuantity;
    }

    public BigDecimal totalUncoveredQuantity() {
        return totalUncoveredQuantity;
    }

    public BigDecimal totalCostBasisUsd() {
        return totalCostBasisUsd;
    }
}
