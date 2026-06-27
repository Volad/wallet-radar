package com.walletradar.liquiditypools.application;

import java.math.BigDecimal;

public record LpSummaryView(
        BigDecimal activeTvlUsd,
        BigDecimal feesEarnedUsd,
        BigDecimal unclaimedUsd,
        int inRange,
        int outOfRange,
        BigDecimal realizedPnlUsd,
        LpFieldPrecision activeTvlPrecision,
        LpFieldPrecision feesEarnedPrecision,
        LpFieldPrecision unclaimedPrecision,
        LpFieldPrecision realizedPnlPrecision
) {
}
