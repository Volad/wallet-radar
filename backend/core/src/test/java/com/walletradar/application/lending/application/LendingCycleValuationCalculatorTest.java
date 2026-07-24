package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LendingCycleValuationCalculatorTest {

    @Test
    void openCycleSubtractsOutstandingBorrowFromUnrealizedPnl() {
        // BASE ETH/USDC: deposit $1335, borrow $600, supply now $1252 → unrealized ~ -$83 (not +$516).
        LendingTotalValuationView result = LendingCycleValuationCalculator.calculate(openInput(
                new BigDecimal("1335.2857593690576"),
                new BigDecimal("600"),
                new BigDecimal("0"),
                new BigDecimal("0.01485388969934016"),
                new BigDecimal("1252.1355618656075")
        ));

        assertThat(result.totalUsdPnl()).isNotNull();
        assertThat(result.totalUsdPnl().doubleValue()).isCloseTo(-735.30, within(0.01));
        assertThat(result.unrealizedTotalUsdPnl()).isNotNull();
        assertThat(result.unrealizedTotalUsdPnl().doubleValue()).isCloseTo(-83.17, within(0.02));
        assertThat(result.unrealizedTotalUsdPnl().doubleValue()).isNotCloseTo(516.83, within(1.0));
    }

    @Test
    void openCycleWithoutBorrowKeepsUnrealizedAsSupplyValuePlusCashflow() {
        LendingTotalValuationView result = LendingCycleValuationCalculator.calculate(openInput(
                new BigDecimal("100"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("101")
        ));

        assertThat(result.totalUsdPnl()).isEqualByComparingTo("-100");
        assertThat(result.unrealizedTotalUsdPnl()).isEqualByComparingTo("1");
    }

    @Test
    void openCycleUsesLiveOutstandingBorrowWhenProvided() {
        // Receipt-less Solana loop: deposited $990 of SOL, borrowed $210 principal, live debt has
        // accrued to $233 and current SOL collateral is $421. Running PnL must subtract the live
        // debt (233), not the 210 principal, so the accrued borrow interest is captured.
        LendingCycleValuationCalculator.Input input = new LendingCycleValuationCalculator.Input(
                "OPEN",
                new BigDecimal("990.87"),      // principalInUsd
                new BigDecimal("200.59"),      // principalOutUsd (a withdraw)
                new BigDecimal("210"),         // borrowedUsd (principal)
                BigDecimal.ZERO,               // repaidUsd
                BigDecimal.ZERO,               // rewardsUsd
                BigDecimal.ZERO,               // feesUsd
                BigDecimal.ZERO,               // gasUsd
                new BigDecimal("421.02"),      // currentUsdValue (live SOL collateral)
                new BigDecimal("233.39"),      // liveOutstandingBorrowUsd (incl. accrued interest)
                null,
                "UNAVAILABLE",
                false,
                false,
                false,
                false
        );

        LendingTotalValuationView result = LendingCycleValuationCalculator.calculate(input);

        // totalUsdPnl = 200.59 + 210 - 990.87 = -580.28
        assertThat(result.totalUsdPnl().doubleValue()).isCloseTo(-580.28, within(0.01));
        // unrealized = -580.28 + 421.02 - 233.39 = -392.65 (uses live 233, not 210 principal)
        assertThat(result.unrealizedTotalUsdPnl().doubleValue()).isCloseTo(-392.65, within(0.01));
    }

    @Test
    void closedCycleDoesNotSubtractOutstandingBorrow() {
        LendingTotalValuationView result = LendingCycleValuationCalculator.calculate(new LendingCycleValuationCalculator.Input(
                "CLOSED",
                new BigDecimal("1000"),
                new BigDecimal("1030"),
                new BigDecimal("500"),
                new BigDecimal("510"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                "UNAVAILABLE",
                false,
                false,
                false,
                false
        ));

        assertThat(result.totalUsdPnl()).isEqualByComparingTo("20");
        assertThat(result.unrealizedTotalUsdPnl()).isNull();
    }

    private static LendingCycleValuationCalculator.Input openInput(
            BigDecimal principalInUsd,
            BigDecimal borrowedUsd,
            BigDecimal repaidUsd,
            BigDecimal feesUsd,
            BigDecimal currentUsdValue
    ) {
        return new LendingCycleValuationCalculator.Input(
                "OPEN",
                principalInUsd,
                BigDecimal.ZERO,
                borrowedUsd,
                repaidUsd,
                BigDecimal.ZERO,
                feesUsd,
                feesUsd,
                currentUsdValue,
                null,
                null,
                "UNAVAILABLE",
                false,
                false,
                false,
                false
        );
    }
}
