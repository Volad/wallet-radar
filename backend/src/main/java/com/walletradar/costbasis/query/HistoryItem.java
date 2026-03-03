package com.walletradar.costbasis.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal read model for transaction history.
 */
public record HistoryItem(
        String eventId,
        String txHash,
        NetworkId networkId,
        String walletAddress,
        Instant blockTimestamp,
        String eventType,
        String assetSymbol,
        String assetContract,
        BigDecimal quantityDelta,
        BigDecimal priceUsd,
        PriceSource priceSource,
        BigDecimal totalValueUsd,
        BigDecimal realisedPnlUsd,
        BigDecimal avcoAtTimeOfSale,
        String status,
        boolean hasOverride
) {
}
