package com.walletradar.lending.application;

import com.walletradar.lending.application.SessionLendingQueryService.LendingFactualApyView;
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
                Map.of("ETH", new BigDecimal("0.02")),
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
                Map.of(),
                Map.of("USDC", new BigDecimal("2152.278542")),
                Map.of("USDC", new BigDecimal("2793.036068")),
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
                Map.of("USDC", new BigDecimal("640.804527")),
                Map.of("USDC", new BigDecimal("2152.278542")),
                Map.of("USDC", new BigDecimal("2793.036068")),
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
                Map.of("USDC", new BigDecimal("5")),
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
                Map.of("USDC", new BigDecimal("30")),
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
    void nonPositiveExposureDurationNullifiesApr() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");

        LendingFactualApyView result = LendingFactualApyCalculator.calculate(new LendingFactualApyCalculator.Input(
                "CLOSED",
                timestamp,
                timestamp,
                Map.of("USDC", new BigDecimal("100")),
                Map.of("USDC", new BigDecimal("1")),
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
