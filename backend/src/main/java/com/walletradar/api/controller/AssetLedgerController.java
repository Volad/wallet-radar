package com.walletradar.api.controller;

import com.walletradar.api.dto.SessionAssetLedgerResponse;
import com.walletradar.costbasis.application.AssetLedgerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-scoped asset ledger history and debug API.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class AssetLedgerController {

    private final AssetLedgerQueryService assetLedgerQueryService;

    @GetMapping("/{sessionId}/asset-ledger")
    public SessionAssetLedgerResponse getSessionAssetLedger(
            @PathVariable String sessionId,
            @RequestParam String familyIdentity
    ) {
        if (familyIdentity == null || familyIdentity.isBlank()) {
            throw new ApiBadRequestException("INVALID_REQUEST", "familyIdentity is required");
        }
        AssetLedgerQueryService.SessionAssetLedgerView view = assetLedgerQueryService
                .findSessionFamilyLedger(normalizedSessionIdOrThrow(sessionId), familyIdentity.trim())
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
        return new SessionAssetLedgerResponse(
                view.sessionId(),
                view.familyIdentity(),
                new SessionAssetLedgerResponse.CurrentState(
                        view.current().quantity(),
                        view.current().coveredQuantity(),
                        view.current().uncoveredQuantity(),
                        view.current().totalCostBasisUsd(),
                        view.current().avcoUsd(),
                        view.current().realisedPnlUsd(),
                        view.current().gasPaidUsd(),
                        view.current().uncoveredBuckets().stream()
                                .map(bucket -> new SessionAssetLedgerResponse.UncoveredBucket(
                                        bucket.walletAddress(),
                                        bucket.networkId(),
                                        bucket.assetSymbol(),
                                        bucket.assetContract(),
                                        bucket.quantity(),
                                        bucket.coveredQuantity(),
                                        bucket.uncoveredQuantity(),
                                        bucket.uncoveredReason(),
                                        bucket.latestTxHash(),
                                        bucket.latestNormalizedType(),
                                        bucket.latestBasisEffect(),
                                        bucket.latestProtocolName(),
                                        bucket.hasIncompleteHistory(),
                                        bucket.hasUnresolvedFlags(),
                                        bucket.unresolvedFlagCount()
                                ))
                                .toList()
                ),
                view.timeline().stream()
                        .map(entry -> new SessionAssetLedgerResponse.TimelineEntry(
                                entry.blockTimestamp(),
                                entry.txHash(),
                                entry.normalizedTransactionId(),
                                entry.normalizedType(),
                                entry.protocolName(),
                                entry.lifecycleKind(),
                                entry.lifecycleStage(),
                                entry.basisEffects(),
                                entry.quantityDelta(),
                                entry.costBasisDeltaUsd(),
                                entry.realisedPnlDeltaUsd(),
                                entry.gasDeltaUsd(),
                                entry.quantityAfter(),
                                entry.coveredQuantityAfter(),
                                entry.uncoveredQuantityAfter(),
                                entry.totalCostBasisAfterUsd(),
                                entry.avcoAfterUsd()
                        ))
                        .toList(),
                view.events().stream()
                        .map(event -> new SessionAssetLedgerResponse.EventOverlay(
                                event.normalizedTransactionId(),
                                event.txHash(),
                                event.blockTimestamp(),
                                event.normalizedType(),
                                event.protocolName(),
                                event.lifecycleKind(),
                                event.walletAddresses(),
                                event.networkIds(),
                                event.flows().stream()
                                        .map(flow -> new SessionAssetLedgerResponse.EventFlow(
                                                flow.role(),
                                                flow.assetContract(),
                                                flow.assetSymbol(),
                                                flow.quantityDelta(),
                                                flow.unitPriceUsd(),
                                                flow.valueUsd(),
                                                flow.priceSource(),
                                                flow.logIndex()
                                        ))
                                        .toList()
                        ))
                        .toList(),
                view.ledgerPoints().stream()
                        .map(point -> new SessionAssetLedgerResponse.LedgerPoint(
                                point.walletAddress(),
                                point.networkId(),
                                point.accountingAssetIdentity(),
                                point.accountingFamilyIdentity(),
                                point.familyDisplaySymbol(),
                                point.assetSymbol(),
                                point.assetContract(),
                                point.normalizedTransactionId(),
                                point.txHash(),
                                point.correlationId(),
                                point.lifecycleChainId(),
                                point.matchedCounterparty(),
                                point.blockTimestamp(),
                                point.replaySequence(),
                                point.normalizedType(),
                                point.lifecycleKind(),
                                point.lifecycleStage(),
                                point.basisEffect(),
                                point.protocolName(),
                                point.quantityDelta(),
                                point.costBasisDeltaUsd(),
                                point.realisedPnlDeltaUsd(),
                                point.gasDeltaUsd(),
                                point.quantityBefore(),
                                point.quantityAfter(),
                                point.totalCostBasisBeforeUsd(),
                                point.totalCostBasisAfterUsd(),
                                point.avcoBeforeUsd(),
                                point.avcoAfterUsd(),
                                point.basisBackedQuantityAfter(),
                                point.uncoveredQuantityDelta(),
                                point.quantityShortfallAfter(),
                                point.uncoveredQuantityAfter(),
                                point.hasIncompleteHistoryAfter(),
                                point.hasUnresolvedFlagsAfter(),
                                point.unresolvedFlagCountAfter()
                        ))
                        .toList()
        );
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
