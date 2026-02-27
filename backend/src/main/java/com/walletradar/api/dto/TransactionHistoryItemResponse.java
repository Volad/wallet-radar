package com.walletradar.api.dto;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaction history item response for asset history endpoint.
 */
public record TransactionHistoryItemResponse(
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
