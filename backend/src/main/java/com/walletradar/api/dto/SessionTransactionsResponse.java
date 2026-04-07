package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/transactions response.
 */
public record SessionTransactionsResponse(
        String sessionId,
        int offset,
        int limit,
        long totalCount,
        boolean hasMore,
        List<Item> items
) {
    public record Item(
            String id,
            String sourceType,
            String txHash,
            String networkId,
            String walletAddress,
            String matchedCounterparty,
            Instant blockTimestamp,
            String type,
            String status,
            String issue,
            String bridgeStatus,
            BigDecimal realisedPnlUsdTotal,
            Long avcoSnapshotVersion,
            List<Flow> flows
    ) {
    }

    public record Flow(
            String role,
            String assetContract,
            String assetSymbol,
            BigDecimal quantityDelta,
            BigDecimal unitPriceUsd,
            BigDecimal valueUsd,
            String priceSource,
            Integer logIndex
    ) {
    }
}
