package com.walletradar.lending.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class LendingFactualApyCalculator {

    static final String NO_YIELD_FLOW_EVIDENCE = "NO_YIELD_FLOW_EVIDENCE";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SECONDS_PER_YEAR = BigDecimal.valueOf(31_536_000L);

    private LendingFactualApyCalculator() {
    }

    static SessionLendingQueryService.LendingFactualApyView calculate(Input input) {
        String unavailableReason = unavailableReason(input);
        if (unavailableReason != null) {
            return unavailable(unavailableReason);
        }
        BigDecimal seconds = BigDecimal.valueOf(Duration.between(input.startTimestamp(), input.endTimestamp()).toSeconds());
        Map<String, BigDecimal> supplyApr = factualSupplyAprByAsset(input, seconds);
        Map<String, BigDecimal> supplyApy = apyByAsset(supplyApr);
        Map<String, BigDecimal> borrowApr = factualBorrowAprByAsset(input, seconds);
        Map<String, BigDecimal> borrowApy = apyByAsset(borrowApr);
        BigDecimal netStrategyApr = null;
        BigDecimal netStrategyApy = null;
        if (input.netStrategyIncomeUsd() != null && input.netCapitalUsd() != null && input.netCapitalUsd().signum() > 0) {
            netStrategyApr = apr(input.netStrategyIncomeUsd(), input.netCapitalUsd(), seconds);
            netStrategyApy = apyFromAprPct(netStrategyApr);
        }
        String apyPrecision = resolveApyPrecision(supplyApr, borrowApr, input);
        String apyUnavailableReason = resolveApyUnavailableReason(apyPrecision, supplyApr, input);
        return new SessionLendingQueryService.LendingFactualApyView(
                supplyApr,
                supplyApy,
                borrowApr,
                borrowApy,
                netStrategyApr,
                netStrategyApy,
                apyPrecision,
                "time-weighted-lifecycle-cashflow",
                apyUnavailableReason,
                LendingMarketRateStatus.PER_SECOND_COMPOUNDING
        );
    }

    private static String unavailableReason(Input input) {
        if ("AMBIGUOUS_NEEDS_REVIEW".equals(input.status())) {
            return "UNRESOLVED_LIFECYCLE";
        }
        if (input.startTimestamp() == null || input.endTimestamp() == null) {
            return "MISSING_CYCLE_TIMESTAMP";
        }
        if (!input.endTimestamp().isAfter(input.startTimestamp())) {
            return "NON_POSITIVE_EXPOSURE_DURATION";
        }
        boolean hasSupplyDenominator = input.openingDepositByAsset().values().stream().anyMatch(value -> value.signum() > 0);
        boolean hasBorrowDenominator = input.borrowedByAsset().values().stream().anyMatch(value -> value.signum() > 0);
        if (!hasSupplyDenominator && !hasBorrowDenominator) {
            return "MISSING_TIME_WEIGHTED_DENOMINATOR";
        }
        return null;
    }

    private static SessionLendingQueryService.LendingFactualApyView unavailable(String reason) {
        return new SessionLendingQueryService.LendingFactualApyView(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                "UNAVAILABLE",
                "time-weighted-lifecycle-cashflow",
                reason,
                LendingMarketRateStatus.PER_SECOND_COMPOUNDING
        );
    }

    private static Map<String, BigDecimal> factualSupplyAprByAsset(Input input, BigDecimal seconds) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : input.openingDepositByAsset().entrySet()) {
            String asset = entry.getKey();
            BigDecimal openingSupplyQty = entry.getValue();
            if (openingSupplyQty == null || openingSupplyQty.signum() <= 0) {
                continue;
            }
            BigDecimal withdrawYieldQty = input.withdrawYieldByAsset().getOrDefault(asset, BigDecimal.ZERO);
            BigDecimal income;
            if (positive(input.internalReceiptMovementByAsset().get(asset))) {
                BigDecimal principalOutCash = input.principalOutCashByAsset().getOrDefault(asset, BigDecimal.ZERO);
                income = principalOutCash.subtract(openingSupplyQty, MC);
            } else if (withdrawYieldQty.signum() > 0) {
                income = withdrawYieldQty;
            } else {
                continue;
            }
            result.put(asset, apr(income, openingSupplyQty, seconds));
        }
        return result;
    }

    private static Map<String, BigDecimal> factualBorrowAprByAsset(Input input, BigDecimal seconds) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : input.borrowedByAsset().entrySet()) {
            String asset = entry.getKey();
            BigDecimal borrowedQty = entry.getValue();
            if (borrowedQty == null || borrowedQty.signum() <= 0) {
                continue;
            }
            BigDecimal accrual;
            if ("OPEN".equals(input.status())) {
                BigDecimal currentDebt = input.currentDebtByAsset().getOrDefault(asset, BigDecimal.ZERO);
                accrual = currentDebt.subtract(borrowedQty, MC);
            } else {
                BigDecimal repaidQty = input.repaidByAsset().getOrDefault(asset, BigDecimal.ZERO);
                accrual = repaidQty.subtract(borrowedQty, MC);
            }
            if (accrual.signum() == 0) {
                continue;
            }
            result.put(asset, apr(accrual, borrowedQty, seconds));
        }
        return result;
    }

    private static String resolveApyPrecision(
            Map<String, BigDecimal> supplyApr,
            Map<String, BigDecimal> borrowApr,
            Input input
    ) {
        if (!supplyApr.isEmpty() || !borrowApr.isEmpty()) {
            return "ESTIMATED";
        }
        boolean hasSupplyExposure = input.openingDepositByAsset().values().stream().anyMatch(value -> value.signum() > 0);
        if (hasSupplyExposure) {
            return "UNAVAILABLE";
        }
        return "OPEN".equals(input.status()) ? "ESTIMATED" : "EXACT";
    }

    private static String resolveApyUnavailableReason(
            String apyPrecision,
            Map<String, BigDecimal> supplyApr,
            Input input
    ) {
        if (!"UNAVAILABLE".equals(apyPrecision)) {
            return null;
        }
        boolean hasSupplyExposure = input.openingDepositByAsset().values().stream().anyMatch(value -> value.signum() > 0);
        if (hasSupplyExposure && supplyApr.isEmpty()) {
            return NO_YIELD_FLOW_EVIDENCE;
        }
        return null;
    }

    private static BigDecimal apr(BigDecimal income, BigDecimal exposure, BigDecimal seconds) {
        return income.divide(exposure, MC)
                .multiply(SECONDS_PER_YEAR, MC)
                .divide(seconds, MC)
                .multiply(ONE_HUNDRED, MC)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static Map<String, BigDecimal> apyByAsset(Map<String, BigDecimal> aprByAsset) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : aprByAsset.entrySet()) {
            result.put(entry.getKey(), apyFromAprPct(entry.getValue()));
        }
        return result;
    }

    private static BigDecimal apyFromAprPct(BigDecimal aprPct) {
        double apr = aprPct.divide(ONE_HUNDRED, MC).doubleValue();
        double base = 1.0d + apr / SECONDS_PER_YEAR.doubleValue();
        if (base <= 0) {
            return null;
        }
        double apy = Math.pow(base, SECONDS_PER_YEAR.doubleValue()) - 1.0d;
        if (!Double.isFinite(apy)) {
            return null;
        }
        return BigDecimal.valueOf(apy).multiply(ONE_HUNDRED, MC).setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    record Input(
            String status,
            Instant startTimestamp,
            Instant endTimestamp,
            Map<String, BigDecimal> openingDepositByAsset,
            Map<String, BigDecimal> withdrawYieldByAsset,
            Map<String, BigDecimal> principalOutCashByAsset,
            Map<String, BigDecimal> internalReceiptMovementByAsset,
            Map<String, BigDecimal> borrowedByAsset,
            Map<String, BigDecimal> repaidByAsset,
            Map<String, BigDecimal> currentDebtByAsset,
            BigDecimal netStrategyIncomeUsd,
            BigDecimal netCapitalUsd
    ) {
    }
}
