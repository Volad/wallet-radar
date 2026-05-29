package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/asset-ledger response.
 */
public record SessionAssetLedgerResponse(
        String sessionId,
        String familyIdentity,
        CurrentState current,
        FullSessionCurrent fullSessionCurrent,
        List<TimelineEntry> timeline,
        List<EventOverlay> events,
        List<LedgerPoint> ledgerPoints
) {
    /**
     * Ledger-based full-session current state: sums all latest replay points for the family
     * (on-chain + Bybit venues) without requiring live balance oracles. Satisfies A2.
     */
    public record FullSessionCurrent(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd
    ) {
    }
    public record CurrentState(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd,
            BigDecimal realisedPnlUsd,
            BigDecimal gasPaidUsd,
            List<UncoveredBucket> uncoveredBuckets,
            List<ShortfallSource> shortfallSources
    ) {
    }

    public record UncoveredBucket(
            String walletAddress,
            String networkId,
            String assetSymbol,
            String assetContract,
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            String uncoveredReason,
            String latestTxHash,
            String latestNormalizedType,
            String latestBasisEffect,
            String latestProtocolName,
            Boolean hasIncompleteHistory,
            Boolean hasUnresolvedFlags,
            Integer unresolvedFlagCount
    ) {
    }

    public record ShortfallSource(
            String walletAddress,
            String networkId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            BigDecimal quantityShortfall
    ) {
    }

    public record TimelineEntry(
            Instant blockTimestamp,
            String txHash,
            String eventGroupId,
            String normalizedTransactionId,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            String lifecycleStage,
            List<String> basisEffects,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityAfter,
            BigDecimal coveredQuantityAfter,
            BigDecimal uncoveredQuantityAfter,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoAfterUsd,
            String avcoKind,
            String fromAddress,
            String toAddress,
            List<String> memberNormalizedTransactionIds
    ) {
    }

    public record EventOverlay(
            String eventGroupId,
            String normalizedTransactionId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            List<String> walletAddresses,
            List<String> networkIds,
            List<EventFlow> flows,
            String fromAddress,
            String toAddress,
            List<String> memberNormalizedTransactionIds
    ) {
    }

    public record EventFlow(
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

    public record LedgerPoint(
            String walletAddress,
            String networkId,
            String accountingAssetIdentity,
            String accountingFamilyIdentity,
            String familyDisplaySymbol,
            String assetSymbol,
            String assetContract,
            String normalizedTransactionId,
            String txHash,
            String correlationId,
            String lifecycleChainId,
            String matchedCounterparty,
            Instant blockTimestamp,
            Long replaySequence,
            String normalizedType,
            String lifecycleKind,
            String lifecycleStage,
            String basisEffect,
            String protocolName,
            BigDecimal quantityDelta,
            BigDecimal costBasisDeltaUsd,
            BigDecimal realisedPnlDeltaUsd,
            BigDecimal gasDeltaUsd,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal totalCostBasisBeforeUsd,
            BigDecimal totalCostBasisAfterUsd,
            BigDecimal avcoBeforeUsd,
            BigDecimal avcoAfterUsd,
            BigDecimal basisBackedQuantityAfter,
            BigDecimal uncoveredQuantityDelta,
            BigDecimal quantityShortfallAfter,
            BigDecimal uncoveredQuantityAfter,
            Boolean hasIncompleteHistoryAfter,
            Boolean hasUnresolvedFlagsAfter,
            Integer unresolvedFlagCountAfter
    ) {
    }
}
