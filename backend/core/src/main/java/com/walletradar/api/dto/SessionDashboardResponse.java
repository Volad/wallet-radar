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
            BigDecimal netAvcoUsd,
            BigDecimal unrealizedPnlPct,
            BigDecimal unrealizedPnlUsd,
            BigDecimal realizedPnlUsd,
            String networkId,
            String walletAddress,
            String issue,
            String valuationModel,
            String valuationUnderlyingSymbol,
            String unsupportedValuationReason,
            /** Wallet domain: EVM, SOLANA, TON, or CEX. */
            String domain,
            /** CEX venue id (e.g. "bybit", "dzengi"); null for on-chain wallets. */
            String venueId,
            /** CEX sub-account kind (e.g. "FUND", "UTA", "EARN"); null if not applicable. */
            String subAccount,
            /** ADR-062 break-even (effective-cost) per unit; null when covered quantity is zero. */
            BigDecimal breakEvenUsd,
            /** ADR-062 realized profit already past break-even. */
            BigDecimal lockedSurplusUsd,
            /** ADR-062 informational zero-basis income booked against this family's cluster. */
            BigDecimal incomeReceivedUsd,
            /** ADR-062 parent family this row's realized P&L contributes to; null when self. */
            String attributionTargetFamily,
            /** ADR-062 deviation guard: coveredQuantity / quantity in [0,1]; null when quantity is zero. */
            BigDecimal coveredRatio,
            /** ADR-062 deviation guard: true when a $0 break-even is a low-coverage artifact, not real. */
            Boolean breakEvenSuppressed,
            /**
             * ADR-062 §5 "Average cost": family-level weighted market cost basis ÷ ETH-equivalent
             * covered quantity (parity with the move-basis header). Equals {@code avcoUsd} for a
             * single-wallet family row; {@code avcoUsd}/{@code netAvcoUsd} stay as demoted diagnostics.
             */
            BigDecimal averageCostUsd
    ) {
    }
}
