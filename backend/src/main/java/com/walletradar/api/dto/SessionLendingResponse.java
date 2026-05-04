package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/sessions/{sessionId}/lending response.
 */
public record SessionLendingResponse(
        String sessionId,
        Summary summary,
        List<Group> groups
) {
    public record Summary(
            BigDecimal totalSuppliedUsd,
            BigDecimal totalBorrowedUsd,
            BigDecimal netExposureUsd,
            Integer openGroups,
            Integer closedGroups,
            Integer protocols
    ) {
    }

    public record Group(
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
            BigDecimal supplyUsd,
            BigDecimal borrowUsd,
            BigDecimal netExposureUsd,
            List<Position> positions,
            List<Cycle> cycles,
            List<HistoryEntry> history
    ) {
    }

    public record Position(
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
            String metricSource
    ) {
    }

    public record HistoryEntry(
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
            String loopId
    ) {
    }

    public record Cycle(
            String id,
            String marketKey,
            String marketLabel,
            String status,
            Instant startTimestamp,
            Instant closeTimestamp,
            String startTxHash,
            String closeTxHash,
            String statusDetail,
            String warningReason,
            Map<String, BigDecimal> assetDenominatedPnlByAsset,
            Map<String, String> assetDenominatedPrecisionByAsset,
            Map<String, String> assetDenominatedReasonByAsset,
            String primaryAssetPnlSummary,
            String largePnlReason,
            List<String> largePnlReasons,
            String primaryLargePnlReason,
            AssetDeltas assetDeltas,
            Pnl realizedPnl,
            Pnl unrealizedPnl,
            PnlBreakdown pnlBreakdown,
            PnlAssetBreakdown pnlAssetBreakdown,
            TotalValuation totalValuation,
            Map<String, List<ObservedFlow>> observedFlowsByAsset,
            BigDecimal peakSupplyUsd,
            BigDecimal peakBorrowUsd,
            Long durationDays,
            List<Position> positions,
            List<HistoryEntry> events,
            List<TxGroup> txGroups
    ) {
    }

    public record AssetDeltas(
            Map<String, BigDecimal> principalInByAsset,
            Map<String, BigDecimal> principalOutByAsset,
            Map<String, BigDecimal> principalOutCashByAsset,
            Map<String, BigDecimal> internalReceiptMovementByAsset,
            Map<String, BigDecimal> borrowedByAsset,
            Map<String, BigDecimal> repaidByAsset,
            Map<String, BigDecimal> withdrawnByAsset,
            Map<String, BigDecimal> rewardByAsset,
            Map<String, BigDecimal> feesByAsset,
            Map<String, BigDecimal> netCashDeltaByAsset
    ) {
    }

    public record Pnl(
            BigDecimal valueUsd,
            String precision,
            String method
    ) {
    }

    public record PnlBreakdown(
            BigDecimal interestEarnedUsd,
            BigDecimal interestPaidUsd,
            BigDecimal gasUsd,
            BigDecimal netPnlUsd,
            String precision,
            String method,
            String reason
    ) {
    }

    public record PnlAssetBreakdown(
            Map<String, BigDecimal> supplyIncomeByAsset,
            Map<String, BigDecimal> borrowCostByAsset,
            Map<String, BigDecimal> rewardsByAsset,
            Map<String, BigDecimal> gasByAsset,
            Map<String, BigDecimal> netIncomeByAsset,
            Map<String, String> precisionByAsset,
            Map<String, String> reasonByAsset
    ) {
    }

    public record TotalValuation(
            BigDecimal principalInUsd,
            BigDecimal principalOutUsd,
            BigDecimal borrowedUsd,
            BigDecimal repaidUsd,
            BigDecimal rewardsUsd,
            BigDecimal feesUsd,
            BigDecimal gasUsd,
            BigDecimal totalUsdPnl,
            BigDecimal currentUsdValue,
            BigDecimal unrealizedTotalUsdPnl,
            String totalUsdPnlPrecision,
            BigDecimal yieldOnlyPnl,
            String yieldOnlyPnlPrecision,
            String valuationMethod,
            String unavailableReason
    ) {
    }

    public record ObservedFlow(
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            String sourceTxHash,
            String sourceKind,
            Boolean isAuthoritativeForPnl,
            String unavailableReason
    ) {
    }

    public record TxGroup(
            String id,
            String type,
            Instant timestamp,
            String dateLabel,
            Integer loopSteps,
            String loopAssetIn,
            String loopAssetOut,
            List<TxItem> items
    ) {
    }

    public record TxItem(
            String id,
            String type,
            String label,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            String txHash,
            Instant blockTimestamp
    ) {
    }
}
