package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;

public record LendingTxItemView(
        String id,
        String type,
        String label,
        String assetSymbol,
        BigDecimal quantity,
        BigDecimal valueUsd,
        String txHash,
        Instant blockTimestamp
) {
}
