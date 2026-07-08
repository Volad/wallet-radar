package com.walletradar.application.lending.view;

import java.math.BigDecimal;

public record LendingPnlView(
        BigDecimal valueUsd,
        String precision,
        String method
) {
}
