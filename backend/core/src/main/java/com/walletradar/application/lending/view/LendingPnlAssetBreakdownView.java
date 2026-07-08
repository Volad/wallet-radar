package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.util.Map;

public record LendingPnlAssetBreakdownView(
        Map<String, BigDecimal> supplyIncomeByAsset,
        Map<String, BigDecimal> borrowCostByAsset,
        Map<String, BigDecimal> rewardsByAsset,
        Map<String, BigDecimal> gasByAsset,
        Map<String, BigDecimal> netIncomeByAsset,
        Map<String, String> precisionByAsset,
        Map<String, String> reasonByAsset,
        Map<String, BigDecimal> supplyPnlUsdByAsset,
        Map<String, BigDecimal> borrowPnlUsdByAsset,
        Map<String, BigDecimal> rewardsUsdByAsset,
        Map<String, BigDecimal> gasUsdByAsset,
        Map<String, BigDecimal> netIncomeUsdByAsset,
        Map<String, String> usdPrecisionByAsset
) {
}
