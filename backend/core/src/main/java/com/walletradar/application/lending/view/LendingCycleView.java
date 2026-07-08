package com.walletradar.application.lending.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LendingCycleView(
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
        LendingAssetDeltasView assetDeltas,
        LendingPnlView realizedPnl,
        LendingPnlView unrealizedPnl,
        LendingPnlBreakdownView pnlBreakdown,
        LendingPnlAssetBreakdownView pnlAssetBreakdown,
        LendingFactualApyView factualApy,
        LendingTotalValuationView totalValuation,
        Map<String, List<LendingObservedFlowView>> observedFlowsByAsset,
        BigDecimal peakSupplyUsd,
        BigDecimal peakBorrowUsd,
        Long durationDays,
        List<LendingPositionView> positions,
        List<LendingHistoryEntryView> events,
        List<LendingTxGroupView> txGroups
) {
}
