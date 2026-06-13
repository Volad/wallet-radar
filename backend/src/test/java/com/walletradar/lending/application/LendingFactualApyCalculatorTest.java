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
