package com.walletradar.api.dto;

import java.math.BigDecimal;
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
            BigDecimal totalRealizedPnlUsd
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
            BigDecimal priceUsd,
            BigDecimal avcoUsd,
            BigDecimal unrealizedPnlPct,
            BigDecimal unrealizedPnlUsd,
            BigDecimal realizedPnlUsd,
            String networkId,
            String walletAddress,
            String issue
    ) {
    }
}
