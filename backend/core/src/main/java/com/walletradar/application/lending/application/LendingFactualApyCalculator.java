package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class LendingFactualApyCalculator {

    static final String NO_YIELD_FLOW_EVIDENCE = "NO_YIELD_FLOW_EVIDENCE";
    static final String SHORT_EXPOSURE_WINDOW = "SHORT_EXPOSURE_WINDOW";
    static final String IMPLAUSIBLE_ANNUALIZATION = "IMPLAUSIBLE_ANNUALIZATION";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SECONDS_PER_YEAR = BigDecimal.valueOf(31_536_000L);
    /**
     * Annualizing an exposure shorter than this window extrapolates a tiny observation over a full
     * year, which is financially meaningless (a few hours of yield projected to 365 days produces
     * astronomically large rates). Below one day we refuse to annualize and report UNAVAILABLE.
     */
    private static final Duration MIN_EXPOSURE_WINDOW = Duration.ofDays(1);
    /**
     * Hard sanity cap on the per-second-compounded APY magnitude. 100000% == 1000x per year; any
     * computed APY beyond this is treated as numerical noise from an implausible period return and
     * is dropped (null) rather than emitted. We do NOT invent or smooth yield: we simply refuse to
     * publish a clearly nonsensical number.
     */
    private static final BigDecimal MAX_APY_PCT = BigDecimal.valueOf(100_000L);

    private LendingFactualApyCalculator() {
    }

    static LendingFactualApyView calculate(Input input) {
        String unavailableReason = unavailableReason(input);
        if (unavailableReason != null) {
            return unavailable(unavailableReason);
        }
        BigDecimal seconds = BigDecimal.valueOf(Duration.between(input.startTimestamp(), input.endTimestamp()).toSeconds());
        Map<String, BigDecimal> supplyApr = factualSupplyAprByAsset(input, seconds);
        Map<String, BigDecimal> supplyApy = apyByAsset(supplyApr);
        Map<String, BigDecimal> borrowApr = factualBorrowAprByAsset(input, seconds);
        Map<String, BigDecimal> borrowApy = apyByAsset(borrowApr);
        boolean netStrategyAttempted = input.netStrategyIncomeUsd() != null
                && input.netCapitalUsd() != null
                && input.netCapitalUsd().signum() > 0;
        BigDecimal netStrategyApr = null;
        BigDecimal netStrategyApy = null;
        if (netStrategyAttempted) {
            BigDecimal candidateApr = apr(input.netStrategyIncomeUsd(), input.netCapitalUsd(), seconds);
            BigDecimal candidateApy = apyFromAprPct(candidateApr);
            // Only surface the net-strategy rates when the annualized APY is plausible; otherwise
            // drop BOTH the APR and APY so we never emit an exploded annualization.
            if (candidateApy != null) {
                netStrategyApr = candidateApr;
                netStrategyApy = candidateApy;
            }
        }
        String apyPrecision = resolveApyPrecision(supplyApr, borrowApr, input);
        String apyUnavailableReason = resolveApyUnavailableReason(apyPrecision, supplyApr, input);
        // Never emit a net-strategy rate alongside an UNAVAILABLE precision.
        if ("UNAVAILABLE".equals(apyPrecision)) {
            netStrategyApr = null;
            netStrategyApy = null;
        }
        // If the net strategy was the only computable signal and it annualized implausibly,
        // mark the whole view UNAVAILABLE with an explicit reason instead of an empty ESTIMATED.
        boolean nothingComputable = supplyApr.isEmpty() && borrowApr.isEmpty() && netStrategyApy == null;
        boolean netStrategyImplausible = netStrategyAttempted && netStrategyApy == null;
        if (nothingComputable && netStrategyImplausible && !"UNAVAILABLE".equals(apyPrecision)) {
            apyPrecision = "UNAVAILABLE";
            apyUnavailableReason = IMPLAUSIBLE_ANNUALIZATION;
        }
        return new LendingFactualApyView(
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
        if (Duration.between(input.startTimestamp(), input.endTimestamp()).compareTo(MIN_EXPOSURE_WINDOW) < 0) {
            return SHORT_EXPOSURE_WINDOW;
        }
        boolean hasSupplyDenominator = hasPositive(input.timeWeightedSupplyPrincipalByAsset());
        boolean hasBorrowDenominator = input.borrowedByAsset().values().stream().anyMatch(value -> value.signum() > 0);
        if (!hasSupplyDenominator && !hasBorrowDenominator) {
            return "MISSING_TIME_WEIGHTED_DENOMINATOR";
        }
        return null;
    }

    private static LendingFactualApyView unavailable(String reason) {
        return new LendingFactualApyView(
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
        // Iterate the exposure map (time-weighted average principal), NOT the first deposit: income
        // accrues on the whole outstanding principal, so the denominator must be time-weighted.
        for (Map.Entry<String, BigDecimal> entry : input.timeWeightedSupplyPrincipalByAsset().entrySet()) {
            String asset = entry.getKey();
            BigDecimal exposureQty = entry.getValue();
            if (exposureQty == null || exposureQty.signum() <= 0) {
                continue;
            }
            // Income cost-basis uses TOTAL principal deposited over the cycle (sum of all deposits),
            // never the first deposit nor the time-weighted average.
            BigDecimal totalPrincipalQty = input.totalSupplyPrincipalByAsset().getOrDefault(asset, exposureQty);
            BigDecimal withdrawYieldQty = input.withdrawYieldByAsset().getOrDefault(asset, BigDecimal.ZERO);
            BigDecimal income;
            if (positive(input.internalReceiptMovementByAsset().get(asset))) {
                BigDecimal principalOutCash = input.principalOutCashByAsset().getOrDefault(asset, BigDecimal.ZERO);
                income = principalOutCash.subtract(totalPrincipalQty, MC);
            } else if (withdrawYieldQty.signum() > 0) {
                income = withdrawYieldQty;
            } else {
                continue;
            }
            result.put(asset, apr(income, exposureQty, seconds));
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
                if (!input.currentDebtByAsset().containsKey(asset)) {
                    continue;
                }
                BigDecimal currentDebt = input.currentDebtByAsset().get(asset);
                BigDecimal repaidQty = input.repaidByAsset().getOrDefault(asset, BigDecimal.ZERO);
                accrual = currentDebt.add(repaidQty, MC).subtract(borrowedQty, MC);
            } else {
                BigDecimal repaidQty = input.repaidByAsset().getOrDefault(asset, BigDecimal.ZERO);
                accrual = repaidQty.subtract(borrowedQty, MC);
            }
            if (accrual.signum() == 0) {
                continue;
            }
            // Denominator = time-weighted average outstanding borrow, falling back to total-ever-borrowed
            // when the timeline is unavailable so nothing regresses to null.
            BigDecimal exposureQty = positive(input.timeWeightedBorrowPrincipalByAsset().get(asset))
                    ? input.timeWeightedBorrowPrincipalByAsset().get(asset)
                    : borrowedQty;
            result.put(asset, apr(accrual, exposureQty, seconds));
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
        boolean hasSupplyExposure = hasPositive(input.timeWeightedSupplyPrincipalByAsset());
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
        boolean hasSupplyExposure = hasPositive(input.timeWeightedSupplyPrincipalByAsset());
        if (hasSupplyExposure && supplyApr.isEmpty()) {
            return NO_YIELD_FLOW_EVIDENCE;
        }
        return null;
    }

    private static boolean hasPositive(Map<String, BigDecimal> source) {
        return source != null && source.values().stream().anyMatch(value -> value != null && value.signum() > 0);
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
            BigDecimal apy = apyFromAprPct(entry.getValue());
            // Omit assets whose annualized APY is null/implausible rather than publishing noise.
            if (apy != null) {
                result.put(entry.getKey(), apy);
            }
        }
        return result;
    }

    private static BigDecimal apyFromAprPct(BigDecimal aprPct) {
        if (aprPct == null) {
            return null;
        }
        double apr = aprPct.divide(ONE_HUNDRED, MC).doubleValue();
        if (!Double.isFinite(apr)) {
            return null;
        }
        double base = 1.0d + apr / SECONDS_PER_YEAR.doubleValue();
        if (base <= 0) {
            return null;
        }
        double apy = Math.pow(base, SECONDS_PER_YEAR.doubleValue()) - 1.0d;
        if (!Double.isFinite(apy)) {
            return null;
        }
        BigDecimal apyPct = BigDecimal.valueOf(apy).multiply(ONE_HUNDRED, MC).setScale(8, RoundingMode.HALF_UP);
        // Guard against implausible magnitudes (exploded annualization of large short-window returns).
        if (apyPct.abs().compareTo(MAX_APY_PCT) > 0) {
            return null;
        }
        return apyPct;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * @param timeWeightedSupplyPrincipalByAsset time-weighted average supply principal over [start, end]
     *                                            — the APR/APY <em>exposure denominator</em> for supply
     *                                            (income accrues on the whole outstanding principal, so
     *                                            the first deposit alone would overstate the rate).
     * @param totalSupplyPrincipalByAsset         total principal deposited over the cycle (sum of every
     *                                            deposit) — the income <em>cost-basis</em> for the
     *                                            internal-receipt-movement branch, never the denominator.
     * @param timeWeightedBorrowPrincipalByAsset  time-weighted average outstanding borrow over [start, end]
     *                                            — the borrow APR exposure denominator (falls back to
     *                                            {@code borrowedByAsset} when the timeline is empty).
     * @param borrowedByAsset                     total-ever-borrowed per asset — drives borrow iteration
     *                                            and the accrual (interest) numerator.
     */
    record Input(
            String status,
            Instant startTimestamp,
            Instant endTimestamp,
            Map<String, BigDecimal> timeWeightedSupplyPrincipalByAsset,
            Map<String, BigDecimal> totalSupplyPrincipalByAsset,
            Map<String, BigDecimal> withdrawYieldByAsset,
            Map<String, BigDecimal> principalOutCashByAsset,
            Map<String, BigDecimal> internalReceiptMovementByAsset,
            Map<String, BigDecimal> timeWeightedBorrowPrincipalByAsset,
            Map<String, BigDecimal> borrowedByAsset,
            Map<String, BigDecimal> repaidByAsset,
            Map<String, BigDecimal> currentDebtByAsset,
            BigDecimal netStrategyIncomeUsd,
            BigDecimal netCapitalUsd
    ) {
    }
}
