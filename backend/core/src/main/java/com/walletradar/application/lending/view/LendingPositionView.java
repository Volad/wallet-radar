package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;

public record LendingPositionView(
        String id,
        String marketKey,
        String side,
        String assetSymbol,
        String underlyingSymbol,
        String assetContract,
        BigDecimal quantity,
        BigDecimal coveredQuantity,
        BigDecimal valueUsd,
        BigDecimal earnedUsd,
        BigDecimal apyPct,
        String metricStatus,
        String metricSource,
        BigDecimal protocolSupplyApyPct,
        BigDecimal protocolBorrowApyPct,
        BigDecimal rewardAprPct,
        BigDecimal netProtocolApyPct,
        String protocolApyStatus,
        String protocolApySource,
        Instant protocolApyCapturedAt,
        Boolean protocolApyStale,
        String rewardAprStatus,
        String rewardAprUnavailableReason,
        String apyConvention
) {
}
