package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import java.math.BigDecimal;
import java.math.MathContext;

final class LendingCycleValuationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private LendingCycleValuationCalculator() {
    }

    static LendingTotalValuationView calculate(Input input) {
        BigDecimal totalUsdPnl = input.principalOutUsd()
                .add(input.borrowedUsd(), MC)
                .add(input.rewardsUsd(), MC)
                .subtract(input.principalInUsd(), MC)
                .subtract(input.repaidUsd(), MC)
                .subtract(input.feesUsd(), MC);

        String unavailableReason = unavailableReason(input);
        String precisionReason = unavailableReason != null
                ? unavailableReason
                : input.hasMissingGasUsdValuation() ? "missing_gas_usd_valuation" : null;
        String totalPrecision = totalPrecision(input, unavailableReason);
        BigDecimal currentUsdValue = "OPEN".equals(input.status()) ? input.currentUsdValue() : null;
        BigDecimal unrealizedTotalUsdPnl = null;
        if (currentUsdValue != null && !"UNAVAILABLE".equals(totalPrecision)) {
            BigDecimal outstandingBorrow = "OPEN".equals(input.status())
                    ? input.borrowedUsd().subtract(input.repaidUsd(), MC).max(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            unrealizedTotalUsdPnl = totalUsdPnl.add(currentUsdValue, MC).subtract(outstandingBorrow, MC);
        }

        return new LendingTotalValuationView(
                input.principalInUsd(),
                input.principalOutUsd(),
                input.borrowedUsd(),
                input.repaidUsd(),
                input.rewardsUsd(),
                input.feesUsd(),
                input.gasUsd(),
                "UNAVAILABLE".equals(totalPrecision) ? null : totalUsdPnl,
                currentUsdValue,
                unrealizedTotalUsdPnl,
                totalPrecision,
                input.yieldOnlyPnl(),
                input.yieldOnlyPrecision(),
                "UNAVAILABLE".equals(totalPrecision)
                        ? "unavailable"
                        : "cycle-economic-cashflow-total-valuation",
                precisionReason
        );
    }

    private static String unavailableReason(Input input) {
        if ("AMBIGUOUS_NEEDS_REVIEW".equals(input.status())) {
            return "unresolved_lifecycle";
        }
        if (input.hasUnresolvedPrincipalExit()) {
            return "unresolved_principal_exit";
        }
        if (input.hasMissingEventValuation()) {
            return "missing_lending_leg_usd_valuation";
        }
        return null;
    }

    private static String totalPrecision(Input input, String unavailableReason) {
        if (unavailableReason != null) {
            return "UNAVAILABLE";
        }
        if ("OPEN".equals(input.status())
                || input.hasWrapperOrShareExposure()
                || input.hasMissingGasUsdValuation()) {
            return "ESTIMATED";
        }
        return "EXACT";
    }

    record Input(
            String status,
            BigDecimal principalInUsd,
            BigDecimal principalOutUsd,
            BigDecimal borrowedUsd,
            BigDecimal repaidUsd,
            BigDecimal rewardsUsd,
            BigDecimal feesUsd,
            BigDecimal gasUsd,
            BigDecimal currentUsdValue,
            BigDecimal yieldOnlyPnl,
            String yieldOnlyPrecision,
            boolean hasWrapperOrShareExposure,
            boolean hasMissingEventValuation,
            boolean hasMissingGasUsdValuation,
            boolean hasUnresolvedPrincipalExit
    ) {
    }
}
