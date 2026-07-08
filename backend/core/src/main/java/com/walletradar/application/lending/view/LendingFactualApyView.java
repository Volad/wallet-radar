package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.util.Map;

public record LendingFactualApyView(
        Map<String, BigDecimal> factualSupplyAprByAsset,
        Map<String, BigDecimal> factualSupplyApyByAsset,
        Map<String, BigDecimal> factualBorrowAprByAsset,
        Map<String, BigDecimal> factualBorrowApyByAsset,
        BigDecimal netStrategyAprPct,
        BigDecimal netStrategyApyPct,
        String apyPrecision,
        String apyMethod,
        String apyUnavailableReason,
        String apyConvention
) {
}
