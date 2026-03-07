package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/transactions response.
 */
public record SessionTransactionsResponse(
        String sessionId,
        List<SessionTransactionItemResponse> items
) {
    public record SessionTransactionItemResponse(
            String id,
            String sourceType,
            String txHash,
            String networkId,
            String walletAddress,
            Instant blockTimestamp,
            String type,
            String bridgeStatus,
            BigDecimal realisedPnlUsdTotal,
            Long avcoSnapshotVersion,
            List<SessionTransactionFlowResponse> flows
    ) {
    }

    public record SessionTransactionFlowResponse(
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
