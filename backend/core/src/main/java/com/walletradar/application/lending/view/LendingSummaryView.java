package com.walletradar.application.lending.view;

import java.math.BigDecimal;

public record LendingSummaryView(
        BigDecimal totalSuppliedUsd,
        BigDecimal totalBorrowedUsd,
        BigDecimal netExposureUsd,
        Integer openGroups,
        Integer closedGroups,
        Integer protocols
) {
}
