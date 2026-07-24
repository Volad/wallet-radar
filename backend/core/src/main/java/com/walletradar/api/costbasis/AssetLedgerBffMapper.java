package com.walletradar.api.costbasis;

import com.walletradar.api.dto.SessionAssetLedgerResponse;
import com.walletradar.application.costbasis.application.AssetLedgerQueryService;
import org.springframework.stereotype.Component;

/**
 * Maps costbasis read-port views to REST DTOs for the asset-ledger BFF surface.
 */
@Component
public class AssetLedgerBffMapper {

    public SessionAssetLedgerResponse toResponse(AssetLedgerQueryService.SessionAssetLedgerView view) {
        return new SessionAssetLedgerResponse(
                view.sessionId(),
                view.familyIdentity(),
                new SessionAssetLedgerResponse.CurrentState(
                        view.current().quantity(),
                        view.current().coveredQuantity(),
                        view.current().uncoveredQuantity(),
                        view.current().totalCostBasisUsd(),
                        view.current().avcoUsd(),
                        view.current().netTotalCostBasisUsd(),
                        view.current().netAvcoUsd(),
                        view.current().realisedPnlUsd(),
                        view.current().netRealisedPnlUsd(),
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
                                .toList(),
                        view.current().shortfallSources().stream()
                                .map(source -> new SessionAssetLedgerResponse.ShortfallSource(
                                        source.walletAddress(),
                                        source.networkId(),
                                        source.txHash(),
                                        source.blockTimestamp(),
                                        source.normalizedType(),
                                        source.protocolName(),
                                        source.quantityShortfall()
                                ))
                                .toList(),
                        view.current().breakEvenUsd(),
                        view.current().averageCostUsd(),
                        view.current().lockedSurplusUsd(),
                        view.current().incomeReceivedUsd(),
                        view.current().attributionTargetFamily(),
                        view.current().familyMemberSymbols(),
                        view.current().coveredRatio(),
                        view.current().breakEvenSuppressed(),
                        view.current().details() == null
                                ? null
                                : new SessionAssetLedgerResponse.DiagnosticLanes(
                                        view.current().details().balanceAvcoUsd(),
                                        view.current().details().balanceNetAvcoUsd(),
                                        view.current().details().blendedAvcoUsd(),
                                        view.current().details().blendedNetAvcoUsd()
                                )
                ),
                new SessionAssetLedgerResponse.FullSessionCurrent(
                        view.fullSessionCurrent().quantity(),
                        view.fullSessionCurrent().coveredQuantity(),
                        view.fullSessionCurrent().uncoveredQuantity(),
                        view.fullSessionCurrent().totalCostBasisUsd(),
                        view.fullSessionCurrent().avcoUsd(),
                        view.fullSessionCurrent().netTotalCostBasisUsd(),
                        view.fullSessionCurrent().netAvcoUsd()
                ),
                view.timeline().stream()
                        .map(entry -> new SessionAssetLedgerResponse.TimelineEntry(
                                entry.blockTimestamp(),
                                entry.txHash(),
                                entry.eventGroupId(),
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
                                entry.avcoBeforeUsd(),
                                entry.avcoAfterUsd(),
                                entry.netTotalCostBasisAfterUsd(),
                                entry.netAvcoBeforeUsd(),
                                entry.netAvcoAfterUsd(),
                                entry.avcoKind(),
                                entry.fromAddress(),
                                entry.toAddress(),
                                entry.memberNormalizedTransactionIds(),
                                entry.blendedAvcoBeforeUsd(),
                                entry.blendedAvcoAfterUsd(),
                                entry.blendedNetAvcoBeforeUsd(),
                                entry.blendedNetAvcoAfterUsd(),
                                entry.blendedCoveredQuantityAfter(),
                                entry.liquidQuantityAfter(),
                                entry.blendedAvcoKind(),
                                entry.effectiveCostAfterUsd(),
                                entry.subjectUnitPriceUsd()
                        ))
                        .toList(),
                view.events().stream()
                        .map(event -> new SessionAssetLedgerResponse.EventOverlay(
                                event.eventGroupId(),
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
                                        .toList(),
                                event.fromAddress(),
                                event.toAddress(),
                                event.memberNormalizedTransactionIds()
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
                                point.netTotalCostBasisBeforeUsd(),
                                point.netTotalCostBasisAfterUsd(),
                                point.netAvcoBeforeUsd(),
                                point.netAvcoAfterUsd(),
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
}
