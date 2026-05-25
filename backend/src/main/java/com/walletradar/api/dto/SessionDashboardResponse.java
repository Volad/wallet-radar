package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/dashboard response.
 */
public record SessionDashboardResponse(
        String sessionId,
        Summary summary,
        List<WalletEntry> wallets,
        List<TokenPositionEntry> tokenPositions
) {
    public record Summary(
            BigDecimal portfolioValueUsd,
            BigDecimal totalUnrealizedPnlUsd,
            BigDecimal totalUnrealizedPnlPct,
            BigDecimal totalRealizedPnlUsd,
            BigDecimal netExternalCapitalUsd,
            BigDecimal lifetimeExternalInflowUsd,
            BigDecimal markToMarketUsd,
            BigDecimal expectedPnlUsd,
            BigDecimal reportedPnlUsd,
            BigDecimal conservationDeltaUsd,
            BigDecimal conservationThresholdUsd,
            boolean conservationBreached
    ) {
    }

    public record WalletEntry(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }

    public record TokenPositionEntry(
            String familyIdentity,
            String symbol,
            String name,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal priceUsd,
            BigDecimal marketValueUsd,
            String priceSource,
            Instant pricedAt,
            Long stalenessSeconds,
            Boolean isLiveQuote,
            String priceIssue,
            BigDecimal avcoUsd,
            BigDecimal unrealizedPnlPct,
            BigDecimal unrealizedPnlUsd,
            BigDecimal realizedPnlUsd,
            String networkId,
            String walletAddress,
            String issue,
            String valuationModel,
            String valuationUnderlyingSymbol,
            String unsupportedValuationReason
    ) {
    }
}
