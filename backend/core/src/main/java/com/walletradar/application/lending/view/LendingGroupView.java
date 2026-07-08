package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LendingGroupView(
        String id,
        String protocol,
        String networkId,
        String walletAddress,
        String status,
        BigDecimal healthFactor,
        String healthLabel,
        BigDecimal healthProgress,
        String healthStatus,
        String healthSource,
        Boolean healthStale,
        Instant lastRefreshedAt,
        BigDecimal supplyUsd,
        BigDecimal borrowUsd,
        BigDecimal netExposureUsd,
        List<LendingPositionView> positions,
        List<LendingCycleView> cycles,
        List<LendingHistoryEntryView> history
) {
}
