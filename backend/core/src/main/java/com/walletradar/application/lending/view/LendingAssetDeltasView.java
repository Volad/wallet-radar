package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.util.Map;

public record LendingAssetDeltasView(
        Map<String, BigDecimal> principalInByAsset,
        Map<String, BigDecimal> principalOutByAsset,
        Map<String, BigDecimal> principalOutCashByAsset,
        Map<String, BigDecimal> internalReceiptMovementByAsset,
        Map<String, BigDecimal> borrowedByAsset,
        Map<String, BigDecimal> repaidByAsset,
        Map<String, BigDecimal> withdrawnByAsset,
        Map<String, BigDecimal> rewardByAsset,
        Map<String, BigDecimal> feesByAsset,
        Map<String, BigDecimal> netCashDeltaByAsset
) {
}
