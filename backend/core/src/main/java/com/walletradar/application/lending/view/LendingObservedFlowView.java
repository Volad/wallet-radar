package com.walletradar.application.lending.view;

import java.math.BigDecimal;

public record LendingObservedFlowView(
        String assetSymbol,
        String assetContract,
        BigDecimal quantity,
        String sourceTxHash,
        String sourceKind,
        Boolean isAuthoritativeForPnl,
        String unavailableReason
) {
}
