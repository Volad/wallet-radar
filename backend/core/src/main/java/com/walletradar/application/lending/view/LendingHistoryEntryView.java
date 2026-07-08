package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record LendingHistoryEntryView(
        String id,
        String txHash,
        String marketKey,
        String cycleId,
        String networkId,
        String walletAddress,
        Instant blockTimestamp,
        String type,
        String eventSubtype,
        String displayType,
        String assetSymbol,
        BigDecimal quantity,
        BigDecimal valueUsd,
        BigDecimal feeUsd,
        Map<String, BigDecimal> feeQuantityByAsset,
        String loopId,
        Map<String, BigDecimal> withdrawYieldByAsset
) {
}
