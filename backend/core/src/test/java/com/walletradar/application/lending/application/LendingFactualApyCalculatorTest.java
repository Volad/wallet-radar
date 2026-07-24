package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LendingFactualApyCalculatorTest {

    @Test
    void supplyAprUsesWithdrawBuyYieldOverOpeningDeposit() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2027-01-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("ETH", new BigDecimal("1")),
                Map.of("ETH", new BigDecimal("1")),
                Map.of("ETH", new BigDecimal("0.02")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("1")
        ));

        assertThat(result.factualSupplyAprByAsset()).containsKey("ETH");
        assertThat(result.factualSupplyAprByAsset().get("ETH")).isEqualByComparingTo("2");
        assertThat(result.apyPrecision()).isEqualTo("ESTIMATED");
    }

    @Test
    void supplyAprUnavailableWhenNoYieldEvidence() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2027-01-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("100")),
                Map.of("USDC", new BigDecimal("100")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("100")
        ));

        assertThat(result.factualSupplyAprByAsset()).isEmpty();
        assertThat(result.apyPrecision()).isEqualTo("UNAVAILABLE");
        assertThat(result.apyUnavailableReason()).isEqualTo(LendingFactualApyCalculator.NO_YIELD_FLOW_EVIDENCE);
    }

    @Test
    void internalReceiptMovementUsesPrincipalOutCashForSupplyApr() {
        Instant start = Instant.parse("2025-07-31T10:56:42Z");
        Instant end = Instant.parse("2025-08-21T07:31:47Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("2595.231191")),
                Map.of("USDC", new BigDecimal("2595.231191")),
                Map.of(),
                Map.of("USDC", new BigDecimal("2152.278542")),
                Map.of("USDC", new BigDecimal("2793.036068")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("2595.231191")
        ));

        assertThat(result.factualSupplyAprByAsset()).containsKey("USDC");
        assertThat(result.factualSupplyAprByAsset().get("USDC")).isNegative();
    }

    @Test
    void internalReceiptMovementOverridesWithdrawBuyYieldForSupplyApr() {
        Instant start = Instant.parse("2025-07-31T10:56:42Z");
        Instant end = Instant.parse("2025-08-21T07:31:47Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("2595.231191")),
                Map.of("USDC", new BigDecimal("2595.231191")),
                Map.of("USDC", new BigDecimal("640.804527")),
                Map.of("USDC", new BigDecimal("2152.278542")),
                Map.of("USDC", new BigDecimal("2793.036068")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("2595.231191")
        ));

        assertThat(result.factualSupplyAprByAsset()).containsKey("USDC");
        assertThat(result.factualSupplyAprByAsset().get("USDC")).isNegative();
    }

    @Test
    void openBorrowAprUsesCurrentDebtBalanceDelta() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "OPEN",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDC", new BigDecimal("600")),
                Map.of("USDC", new BigDecimal("600")),
                Map.of(),
                Map.of("USDC", new BigDecimal("612")),
                null,
                BigDecimal.ZERO
        ));

        assertThat(result.factualBorrowAprByAsset()).containsKey("USDC");
        assertThat(result.factualBorrowAprByAsset().get("USDC")).isPositive();
    }

    @Test
    void openBorrowAprZeroWhenSyntheticOutstandingMatchesBorrowMinusRepaid() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "OPEN",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDE", new BigDecimal("5000")),
                Map.of("USDE", new BigDecimal("5000")),
                Map.of("USDE", new BigDecimal("2503.72")),
                Map.of("USDE", new BigDecimal("2496.28")),
                null,
                BigDecimal.ZERO
        ));

        assertThat(result.factualBorrowAprByAsset()).doesNotContainKey("USDE");
        assertThat(result.factualBorrowApyByAsset()).doesNotContainKey("USDE");
    }

    @Test
    void openBorrowSkipsFactualAprWhenCurrentDebtUnknown() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "OPEN",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDC", new BigDecimal("600")),
                Map.of("USDC", new BigDecimal("600")),
                Map.of(),
                Map.of(),
                null,
                BigDecimal.ZERO
        ));

        assertThat(result.factualBorrowAprByAsset()).doesNotContainKey("USDC");
        assertThat(result.factualBorrowApyByAsset()).doesNotContainKey("USDC");
    }

    @Test
    void shortExposureWindowIsUnavailableInsteadOfExploding() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-01T06:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "OPEN",
                start,
                end,
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("5")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                new BigDecimal("5"),
                new BigDecimal("1000")
        ));

        assertThat(result.apyPrecision()).isEqualTo("UNAVAILABLE");
        assertThat(result.apyUnavailableReason()).isEqualTo(LendingFactualApyCalculator.SHORT_EXPOSURE_WINDOW);
        assertThat(result.factualSupplyApyByAsset()).isEmpty();
        assertThat(result.netStrategyApyPct()).isNull();
        assertThat(result.netStrategyAprPct()).isNull();
    }

    @Test
    void implausiblePeriodReturnDropsNetStrategyApyAndCapsPerAssetApy() {
        // Large period return over a short-but-valid window: APR annualizes to thousands of percent,
        // and per-second compounding would explode the APY far beyond any plausible magnitude.
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-03T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "OPEN",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDE", new BigDecimal("5000")),
                Map.of("USDE", new BigDecimal("5000")),
                Map.of(),
                Map.of("USDE", new BigDecimal("7500")),
                new BigDecimal("2500"),
                new BigDecimal("5000")
        ));

        // Net strategy annualizes to an implausible APY => both APR and APY are dropped.
        assertThat(result.netStrategyApyPct()).isNull();
        assertThat(result.netStrategyAprPct()).isNull();
        // Per-asset borrow APY is capped out (omitted) even though the raw APR is retained.
        assertThat(result.factualBorrowApyByAsset()).doesNotContainKey("USDE");
        assertAllApyWithinCap(result);
    }

    @Test
    void normalMultiMonthCycleProducesSaneApy() {
        // ~3% over ~6 months => low double-digit annualized APY, comfortably below the cap.
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-07-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("30")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                new BigDecimal("30"),
                new BigDecimal("1000")
        ));

        assertThat(result.apyPrecision()).isEqualTo("ESTIMATED");
        assertThat(result.factualSupplyApyByAsset()).containsKey("USDC");
        BigDecimal supplyApy = result.factualSupplyApyByAsset().get("USDC");
        assertThat(supplyApy).isGreaterThan(new BigDecimal("5"));
        assertThat(supplyApy).isLessThan(new BigDecimal("20"));
        assertThat(result.netStrategyApyPct()).isNotNull();
        assertAllApyWithinCap(result);
    }

    private static void assertAllApyWithinCap(LendingFactualApyView result) {
        BigDecimal cap = new BigDecimal("100000");
        result.factualSupplyApyByAsset().values().forEach(value ->
                assertThat(value.abs()).isLessThanOrEqualTo(cap));
        result.factualBorrowApyByAsset().values().forEach(value ->
                assertThat(value.abs()).isLessThanOrEqualTo(cap));
        if (result.netStrategyApyPct() != null) {
            assertThat(result.netStrategyApyPct().abs()).isLessThanOrEqualTo(cap);
        }
    }

    @Test
    void supplyAprUsesTimeWeightedPrincipalNotFirstDeposit() {
        // Multi-deposit cycle: 500 for the first half of the year, then a second 500 deposit lifts the
        // principal to 1000 for the second half => time-weighted average principal = 750, total = 1000.
        // Income accrues on the whole outstanding principal, so the exposure denominator must be the
        // time-weighted average (750), NOT the first deposit (500) which overstates the rate.
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2027-01-01T00:00:00Z");

        LendingFactualApyView timeWeighted = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("750")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("30")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("1000")
        ));

        LendingFactualApyView firstDepositOnly = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of("USDC", new BigDecimal("500")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("30")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("1000")
        ));

        // 30 / 750 over a full year => 4.00% (time-weighted); 30 / 500 => 6.00% (first-deposit denom).
        assertThat(timeWeighted.factualSupplyAprByAsset().get("USDC")).isEqualByComparingTo("4");
        assertThat(firstDepositOnly.factualSupplyAprByAsset().get("USDC")).isEqualByComparingTo("6");
        assertThat(timeWeighted.factualSupplyAprByAsset().get("USDC"))
                .isLessThan(firstDepositOnly.factualSupplyAprByAsset().get("USDC"));
    }

    @Test
    void borrowAprUsesTimeWeightedOutstandingNotTotalEverBorrowed() {
        // Borrowed in two 500 tranches (total-ever-borrowed = 1000) but the time-weighted average
        // outstanding borrow over the window is 750. Interest accrual = repaid − borrowed = 50. Using
        // the time-weighted denominator (750) reflects real exposure; total-ever-borrowed (1000) would
        // understate the borrow APR.
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2027-01-01T00:00:00Z");

        LendingFactualApyView timeWeighted = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDC", new BigDecimal("750")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("1050")),
                Map.of(),
                null,
                BigDecimal.ZERO
        ));

        LendingFactualApyView totalBorrowedDenom = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                start,
                end,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("1000")),
                Map.of("USDC", new BigDecimal("1050")),
                Map.of(),
                null,
                BigDecimal.ZERO
        ));

        // 50 / 750 => 6.666...% (time-weighted); 50 / 1000 => 5.00% (total-ever-borrowed denom).
        assertThat(timeWeighted.factualBorrowAprByAsset().get("USDC")).isEqualByComparingTo("6.66666667");
        assertThat(totalBorrowedDenom.factualBorrowAprByAsset().get("USDC")).isEqualByComparingTo("5");
        assertThat(timeWeighted.factualBorrowAprByAsset().get("USDC"))
                .isGreaterThan(totalBorrowedDenom.factualBorrowAprByAsset().get("USDC"));
    }

    @Test
    void nonPositiveExposureDurationNullifiesApr() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                timestamp,
                timestamp,
                Map.of("USDC", new BigDecimal("100")),
                Map.of("USDC", new BigDecimal("100")),
                Map.of("USDC", new BigDecimal("1")),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                new BigDecimal("100")
        ));

        assertThat(result.apyPrecision()).isEqualTo("UNAVAILABLE");
        assertThat(result.apyUnavailableReason()).isEqualTo("NON_POSITIVE_EXPOSURE_DURATION");
    }
}
