package com.walletradar.lending.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class LendingFactualApyCalculator {

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
        Map<String, BigDecimal> supplyApr = aprByAsset(input.principalInByAsset(), input.supplyIncomeByAsset(), seconds);
        Map<String, BigDecimal> supplyApy = apyByAsset(supplyApr);
        Map<String, BigDecimal> borrowApr = aprByAsset(input.borrowedByAsset(), input.borrowCostByAsset(), seconds);
        Map<String, BigDecimal> borrowApy = apyByAsset(borrowApr);
        BigDecimal netStrategyApr = null;
        BigDecimal netStrategyApy = null;
        if (input.netStrategyIncomeUsd() != null && input.netCapitalUsd() != null && input.netCapitalUsd().signum() > 0) {
            netStrategyApr = apr(input.netStrategyIncomeUsd(), input.netCapitalUsd(), seconds);
            netStrategyApy = apyFromAprPct(netStrategyApr);
        }
        return new SessionLendingQueryService.LendingFactualApyView(
                supplyApr,
                supplyApy,
                borrowApr,
                borrowApy,
                netStrategyApr,
                netStrategyApy,
                "OPEN".equals(input.status()) ? "ESTIMATED" : "EXACT",
                "time-weighted-lifecycle-cashflow",
                null,
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
        boolean hasSupplyDenominator = input.principalInByAsset().values().stream().anyMatch(value -> value.signum() > 0);
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

    private static Map<String, BigDecimal> aprByAsset(
            Map<String, BigDecimal> exposureByAsset,
            Map<String, BigDecimal> incomeByAsset,
            BigDecimal seconds
    ) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : exposureByAsset.entrySet()) {
            BigDecimal exposure = entry.getValue();
            if (exposure == null || exposure.signum() <= 0) {
                continue;
            }
            BigDecimal income = incomeByAsset.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            result.put(entry.getKey(), apr(income, exposure, seconds));
        }
        return result;
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

    record Input(
            String status,
            Instant startTimestamp,
            Instant endTimestamp,
            Map<String, BigDecimal> principalInByAsset,
            Map<String, BigDecimal> supplyIncomeByAsset,
            Map<String, BigDecimal> borrowedByAsset,
            Map<String, BigDecimal> borrowCostByAsset,
            BigDecimal netStrategyIncomeUsd,
            BigDecimal netCapitalUsd
    ) {
    }
}
