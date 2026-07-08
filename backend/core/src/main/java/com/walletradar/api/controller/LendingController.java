package com.walletradar.api.controller;

import com.walletradar.application.lending.view.*;
import com.walletradar.api.dto.RefreshStatusResponse;
import com.walletradar.api.dto.SessionLendingResponse;
import com.walletradar.application.lending.application.LendingGroupRefreshStateService;
import com.walletradar.application.lending.application.LendingRefreshOrchestrator;
import com.walletradar.application.lending.application.SessionLendingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Session-scoped lending workspace API.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class LendingController {

    private final SessionLendingQueryService sessionLendingQueryService;
    private final LendingRefreshOrchestrator refreshOrchestrator;
    private final LendingGroupRefreshStateService refreshStateService;

    @GetMapping("/{sessionId}/lending")
    public SessionLendingResponse getSessionLending(@PathVariable String sessionId) {
        return loadSessionLending(normalizedSessionIdOrThrow(sessionId));
    }

    @GetMapping("/{sessionId}/lending/refresh-status")
    public RefreshStatusResponse getLendingRefreshStatus(@PathVariable String sessionId) {
        String normalized = normalizedSessionIdOrThrow(sessionId);
        sessionLendingQueryService.findSessionLending(normalized)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
        return refreshStateService.getStatus(normalized);
    }

    @PostMapping("/{sessionId}/lending/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RefreshStatusResponse refreshAllLending(@PathVariable String sessionId) {
        String normalized = normalizedSessionIdOrThrow(sessionId);
        sessionLendingQueryService.findSessionLending(normalized)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
        refreshOrchestrator.triggerRefreshAllOpenGroups(normalized);
        return refreshStateService.getStatus(normalized);
    }

    @PostMapping("/{sessionId}/lending/groups/{groupKey}/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RefreshStatusResponse refreshGroup(
            @PathVariable String sessionId,
            @PathVariable String groupKey
    ) {
        String normalized = normalizedSessionIdOrThrow(sessionId);
        String normalizedGroupKey = groupKey.trim().toLowerCase(Locale.ROOT);
        if (!sessionLendingQueryService.ownsGroupId(normalized, normalizedGroupKey)) {
            throw new ApiNotFoundException("LENDING_GROUP_NOT_FOUND", "Lending group not found for session");
        }
        refreshOrchestrator.triggerRefreshGroup(normalized, normalizedGroupKey);
        return refreshStateService.getStatus(normalized);
    }

    private SessionLendingResponse loadSessionLending(String sessionId) {
        return sessionLendingQueryService.findSessionLending(sessionId)
                .map(view -> new SessionLendingResponse(
                        view.sessionId(),
                        new SessionLendingResponse.Summary(
                                view.summary().totalSuppliedUsd(),
                                view.summary().totalBorrowedUsd(),
                                view.summary().netExposureUsd(),
                                view.summary().openGroups(),
                                view.summary().closedGroups(),
                                view.summary().protocols()
                        ),
                        view.groups().stream()
                                .map(group -> new SessionLendingResponse.Group(
                                        group.id(),
                                        group.protocol(),
                                        group.networkId(),
                                        group.walletAddress(),
                                        group.status(),
                                        group.healthFactor(),
                                        group.healthLabel(),
                                        group.healthProgress(),
                                        group.healthStatus(),
                                        group.healthSource(),
                                        group.healthStale(),
                                        group.lastRefreshedAt(),
                                        group.supplyUsd(),
                                        group.borrowUsd(),
                                        group.netExposureUsd(),
                                        group.positions().stream()
                                                .map(this::toPosition)
                                                .toList(),
                                        group.cycles().stream()
                                                .map(cycle -> new SessionLendingResponse.Cycle(
                                                        cycle.id(),
                                                        cycle.marketKey(),
                                                        cycle.marketLabel(),
                                                        cycle.status(),
                                                        cycle.startTimestamp(),
                                                        cycle.closeTimestamp(),
                                                        cycle.startTxHash(),
                                                        cycle.closeTxHash(),
                                                        cycle.statusDetail(),
                                                        cycle.warningReason(),
                                                        cycle.assetDenominatedPnlByAsset(),
                                                        cycle.assetDenominatedPrecisionByAsset(),
                                                        cycle.assetDenominatedReasonByAsset(),
                                                        cycle.primaryAssetPnlSummary(),
                                                        cycle.largePnlReason(),
                                                        cycle.largePnlReasons(),
                                                        cycle.primaryLargePnlReason(),
                                                        new SessionLendingResponse.AssetDeltas(
                                                                cycle.assetDeltas().principalInByAsset(),
                                                                cycle.assetDeltas().principalOutByAsset(),
                                                                cycle.assetDeltas().principalOutCashByAsset(),
                                                                cycle.assetDeltas().internalReceiptMovementByAsset(),
                                                                cycle.assetDeltas().borrowedByAsset(),
                                                                cycle.assetDeltas().repaidByAsset(),
                                                                cycle.assetDeltas().withdrawnByAsset(),
                                                                cycle.assetDeltas().rewardByAsset(),
                                                                cycle.assetDeltas().feesByAsset(),
                                                                cycle.assetDeltas().netCashDeltaByAsset()
                                                        ),
                                                        new SessionLendingResponse.Pnl(
                                                                cycle.realizedPnl().valueUsd(),
                                                                cycle.realizedPnl().precision(),
                                                                cycle.realizedPnl().method()
                                                        ),
                                                        new SessionLendingResponse.Pnl(
                                                                cycle.unrealizedPnl().valueUsd(),
                                                                cycle.unrealizedPnl().precision(),
                                                                cycle.unrealizedPnl().method()
                                                        ),
                                                        new SessionLendingResponse.PnlBreakdown(
                                                                cycle.pnlBreakdown().interestEarnedUsd(),
                                                                cycle.pnlBreakdown().interestPaidUsd(),
                                                                cycle.pnlBreakdown().gasUsd(),
                                                                cycle.pnlBreakdown().netPnlUsd(),
                                                                cycle.pnlBreakdown().precision(),
                                                                cycle.pnlBreakdown().method(),
                                                                cycle.pnlBreakdown().reason()
                                                        ),
                                                        new SessionLendingResponse.PnlAssetBreakdown(
                                                                cycle.pnlAssetBreakdown().supplyIncomeByAsset(),
                                                                cycle.pnlAssetBreakdown().borrowCostByAsset(),
                                                                cycle.pnlAssetBreakdown().rewardsByAsset(),
                                                                cycle.pnlAssetBreakdown().gasByAsset(),
                                                                cycle.pnlAssetBreakdown().netIncomeByAsset(),
                                                                cycle.pnlAssetBreakdown().precisionByAsset(),
                                                                cycle.pnlAssetBreakdown().reasonByAsset(),
                                                                cycle.pnlAssetBreakdown().supplyPnlUsdByAsset(),
                                                                cycle.pnlAssetBreakdown().borrowPnlUsdByAsset(),
                                                                cycle.pnlAssetBreakdown().rewardsUsdByAsset(),
                                                                cycle.pnlAssetBreakdown().gasUsdByAsset(),
                                                                cycle.pnlAssetBreakdown().netIncomeUsdByAsset(),
                                                                cycle.pnlAssetBreakdown().usdPrecisionByAsset()
                                                        ),
                                                        new SessionLendingResponse.FactualApy(
                                                                cycle.factualApy().factualSupplyAprByAsset(),
                                                                cycle.factualApy().factualSupplyApyByAsset(),
                                                                cycle.factualApy().factualBorrowAprByAsset(),
                                                                cycle.factualApy().factualBorrowApyByAsset(),
                                                                cycle.factualApy().netStrategyAprPct(),
                                                                cycle.factualApy().netStrategyApyPct(),
                                                                cycle.factualApy().apyPrecision(),
                                                                cycle.factualApy().apyMethod(),
                                                                cycle.factualApy().apyUnavailableReason(),
                                                                cycle.factualApy().apyConvention()
                                                        ),
                                                        new SessionLendingResponse.TotalValuation(
                                                                cycle.totalValuation().principalInUsd(),
                                                                cycle.totalValuation().principalOutUsd(),
                                                                cycle.totalValuation().borrowedUsd(),
                                                                cycle.totalValuation().repaidUsd(),
                                                                cycle.totalValuation().rewardsUsd(),
                                                                cycle.totalValuation().feesUsd(),
                                                                cycle.totalValuation().gasUsd(),
                                                                cycle.totalValuation().totalUsdPnl(),
                                                                cycle.totalValuation().currentUsdValue(),
                                                                cycle.totalValuation().unrealizedTotalUsdPnl(),
                                                                cycle.totalValuation().totalUsdPnlPrecision(),
                                                                cycle.totalValuation().yieldOnlyPnl(),
                                                                cycle.totalValuation().yieldOnlyPnlPrecision(),
                                                                cycle.totalValuation().valuationMethod(),
                                                                cycle.totalValuation().unavailableReason()
                                                        ),
                                                        cycle.observedFlowsByAsset().entrySet().stream()
                                                                .collect(java.util.stream.Collectors.toMap(
                                                                        java.util.Map.Entry::getKey,
                                                                        entry -> entry.getValue().stream()
                                                                                .map(flow -> new SessionLendingResponse.ObservedFlow(
                                                                                        flow.assetSymbol(),
                                                                                        flow.assetContract(),
                                                                                        flow.quantity(),
                                                                                        flow.sourceTxHash(),
                                                                                        flow.sourceKind(),
                                                                                        flow.isAuthoritativeForPnl(),
                                                                                        flow.unavailableReason()
                                                                                ))
                                                                                .toList(),
                                                                        (left, right) -> left,
                                                                        java.util.LinkedHashMap::new
                                                                )),
                                                        cycle.peakSupplyUsd(),
                                                        cycle.peakBorrowUsd(),
                                                        cycle.durationDays(),
                                                        cycle.positions().stream()
                                                                .map(this::toPosition)
                                                                .toList(),
                                                        cycle.events().stream()
                                                                .map(this::toHistoryEntry)
                                                                .toList(),
                                                        cycle.txGroups().stream()
                                                                .map(txGroup -> new SessionLendingResponse.TxGroup(
                                                                        txGroup.id(),
                                                                        txGroup.type(),
                                                                        txGroup.timestamp(),
                                                                        txGroup.dateLabel(),
                                                                        txGroup.loopSteps(),
                                                                        txGroup.loopAssetIn(),
                                                                        txGroup.loopAssetOut(),
                                                                        txGroup.items().stream()
                                                                                .map(item -> new SessionLendingResponse.TxItem(
                                                                                        item.id(),
                                                                                        item.type(),
                                                                                        item.label(),
                                                                                        item.assetSymbol(),
                                                                                        item.quantity(),
                                                                                        item.valueUsd(),
                                                                                        item.txHash(),
                                                                                        item.blockTimestamp()
                                                                                ))
                                                                                .toList()
                                                                ))
                                                                .toList()
                                                ))
                                                .toList(),
                                        group.history().stream()
                                                .map(this::toHistoryEntry)
                                                .toList()
                                ))
                                .toList()
                ))
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }

    private SessionLendingResponse.Position toPosition(LendingPositionView position) {
        return new SessionLendingResponse.Position(
                position.id(),
                position.marketKey(),
                position.side(),
                position.assetSymbol(),
                position.underlyingSymbol(),
                position.assetContract(),
                position.quantity(),
                position.coveredQuantity(),
                position.valueUsd(),
                position.earnedUsd(),
                position.apyPct(),
                position.metricStatus(),
                position.metricSource(),
                position.protocolSupplyApyPct(),
                position.protocolBorrowApyPct(),
                position.rewardAprPct(),
                position.netProtocolApyPct(),
                position.protocolApyStatus(),
                position.protocolApySource(),
                position.protocolApyCapturedAt(),
                position.protocolApyStale(),
                position.rewardAprStatus(),
                position.rewardAprUnavailableReason(),
                position.apyConvention()
        );
    }

    private SessionLendingResponse.HistoryEntry toHistoryEntry(
            LendingHistoryEntryView history
    ) {
        return new SessionLendingResponse.HistoryEntry(
                history.id(),
                history.txHash(),
                history.marketKey(),
                history.cycleId(),
                history.networkId(),
                history.walletAddress(),
                history.blockTimestamp(),
                history.type(),
                history.eventSubtype(),
                history.displayType(),
                history.assetSymbol(),
                history.quantity(),
                history.valueUsd(),
                history.feeUsd(),
                history.loopId()
        );
    }
}
