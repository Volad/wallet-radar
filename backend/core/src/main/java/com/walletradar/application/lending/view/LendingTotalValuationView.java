package com.walletradar.application.lending.view;

import java.math.BigDecimal;

public record LendingTotalValuationView(
        BigDecimal principalInUsd,
        BigDecimal principalOutUsd,
        BigDecimal borrowedUsd,
        BigDecimal repaidUsd,
        BigDecimal rewardsUsd,
        BigDecimal feesUsd,
        BigDecimal gasUsd,
        BigDecimal totalUsdPnl,
        BigDecimal currentUsdValue,
        BigDecimal unrealizedTotalUsdPnl,
        String totalUsdPnlPrecision,
        BigDecimal yieldOnlyPnl,
        String yieldOnlyPnlPrecision,
        String valuationMethod,
        String unavailableReason
) {
}
