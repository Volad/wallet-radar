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
        List<TimelineEntry> timeline,
        List<EventOverlay> events,
        List<LedgerPoint> ledgerPoints
) {
    public record CurrentState(
            BigDecimal quantity,
            BigDecimal coveredQuantity,
            BigDecimal uncoveredQuantity,
            BigDecimal totalCostBasisUsd,
            BigDecimal avcoUsd,
            BigDecimal realisedPnlUsd,
            BigDecimal gasPaidUsd
    ) {
    }

    public record TimelineEntry(
            Instant blockTimestamp,
            String txHash,
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
            BigDecimal avcoAfterUsd
    ) {
    }

    public record EventOverlay(
            String normalizedTransactionId,
            String txHash,
            Instant blockTimestamp,
            String normalizedType,
            String protocolName,
            String lifecycleKind,
            List<String> walletAddresses,
            List<String> networkIds,
            List<EventFlow> flows
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
