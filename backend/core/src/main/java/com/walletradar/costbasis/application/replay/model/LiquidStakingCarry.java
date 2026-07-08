package com.walletradar.costbasis.application.replay.model;

import java.math.BigDecimal;
import java.math.MathContext;

public final class LiquidStakingCarry {

    private static final MathContext MC = MathContext.DECIMAL128;

    private BigDecimal totalSourceQuantity = BigDecimal.ZERO;
    private BigDecimal totalCoveredQuantity = BigDecimal.ZERO;
    private BigDecimal totalUncoveredQuantity = BigDecimal.ZERO;
    private BigDecimal totalCostBasisUsd = BigDecimal.ZERO;
    /** ADR-040 Change 2: net cost lane — tracks the lower reward-discounted basis. */
    private BigDecimal totalNetCostBasisUsd = BigDecimal.ZERO;

    public void add(CarryTransfer carry) {
        if (carry == null) {
            return;
        }
        totalSourceQuantity = totalSourceQuantity.add(carry.quantity(), MC);
        totalCoveredQuantity = totalCoveredQuantity.add(carry.coveredQuantity(), MC);
        totalUncoveredQuantity = totalUncoveredQuantity.add(carry.uncoveredQuantity(), MC);
        totalCostBasisUsd = totalCostBasisUsd.add(carry.costBasisUsd(), MC);
        BigDecimal net = carry.netCostBasisUsd() != null ? carry.netCostBasisUsd() : carry.costBasisUsd();
        totalNetCostBasisUsd = totalNetCostBasisUsd.add(net, MC);
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

    /** ADR-040 Change 2: net cost basis sum across all outbound legs. */
    public BigDecimal totalNetCostBasisUsd() {
        return totalNetCostBasisUsd;
    }
}
