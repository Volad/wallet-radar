package com.walletradar.application.lending.view;

import java.math.BigDecimal;

public record LendingPnlBreakdownView(
        BigDecimal interestEarnedUsd,
        BigDecimal interestPaidUsd,
        BigDecimal gasUsd,
        BigDecimal netPnlUsd,
        String precision,
        String method,
        String reason
) {
}
