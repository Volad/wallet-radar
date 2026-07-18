package com.walletradar.api.portfolio;

import com.walletradar.api.dto.RebuildSessionTransactionsResponse;
import com.walletradar.api.dto.SessionBackfillStatusResponse;
import com.walletradar.api.dto.SessionDashboardResponse;
import com.walletradar.api.dto.SessionResponse;
import com.walletradar.api.dto.SessionTransactionsResponse;
import com.walletradar.application.portfolio.application.SessionDashboardQueryService;
import com.walletradar.application.portfolio.application.SessionQueryService;
import com.walletradar.application.portfolio.application.SessionTransactionsQueryService;
import org.springframework.stereotype.Component;

/**
 * Maps portfolio read-port views to REST DTOs. GET portfolio paths stay read-only and must not
 * import {@code platform.networks} RPC adapters (see {@code ModuleBoundaryTest}).
 */
@Component
public class SessionPortfolioBffMapper {

    public SessionResponse toSessionResponse(SessionQueryService.SessionView view) {
        return new SessionResponse(
                view.sessionId(),
                view.wallets().stream()
                        .map(w -> new SessionResponse.SessionWalletEntry(
                                w.address(),
                                w.label(),
                                w.color(),
                                w.networks().stream().map(Enum::name).toList()
                        ))
                        .toList()
        );
    }

    public SessionBackfillStatusResponse toBackfillStatusResponse(SessionQueryService.SessionBackfillStatusView view) {
        return new SessionBackfillStatusResponse(
                view.sessionId(),
                view.status(),
                view.acquisitionStatus(),
                view.overallProgressPct(),
                view.totalTargets(),
                view.completedTargets(),
                view.pipelineStage(),
                view.pipelineStatus(),
                view.pipelineMessage(),
                view.phaseProgress() == null ? null : new SessionBackfillStatusResponse.PhaseProgress(
                        view.phaseProgress().phase(),
                        view.phaseProgress().progressPct(),
                        view.phaseProgress().processedCount(),
                        view.phaseProgress().leftCount(),
                        view.phaseProgress().totalCount()
                ),
                view.lastSyncedAt(),
                view.wallets().stream()
                        .map(wallet -> new SessionBackfillStatusResponse.SessionWalletBackfillStatus(
                                wallet.address(),
                                wallet.label(),
                                wallet.color(),
                                wallet.networks().stream()
                                        .map(network -> new SessionBackfillStatusResponse.NetworkBackfillStatus(
                                                network.networkId(),
                                                network.status(),
                                                network.progressPct(),
                                                network.lastBlockSynced(),
                                                network.backfillComplete(),
                                                network.syncBannerMessage()
                                        ))
                                        .toList()
                        ))
                        .toList()
        );
    }

    public SessionDashboardResponse toDashboardResponse(SessionDashboardQueryService.SessionDashboardView view) {
        return new SessionDashboardResponse(
                view.sessionId(),
                new SessionDashboardResponse.Summary(
                        view.summary().portfolioValueUsd(),
                        view.summary().totalUnrealizedPnlUsd(),
                        view.summary().totalUnrealizedPnlPct(),
                        view.summary().totalRealizedPnlUsd(),
                        view.summary().netExternalCapitalUsd(),
                        view.summary().lifetimeExternalInflowUsd(),
                        view.summary().markToMarketUsd(),
                        view.summary().expectedPnlUsd(),
                        view.summary().reportedPnlUsd(),
                        view.summary().conservationDeltaUsd(),
                        view.summary().conservationThresholdUsd(),
                        view.summary().conservationBreached()
                ),
                view.wallets().stream()
                        .map(wallet -> new SessionDashboardResponse.WalletEntry(
                                wallet.address(),
                                wallet.label(),
                                wallet.color(),
                                wallet.networks()
                        ))
                        .toList(),
                view.tokenPositions().stream()
                        .map(position -> new SessionDashboardResponse.TokenPositionEntry(
                                position.familyIdentity(),
                                position.symbol(),
                                position.name(),
                                position.quantity(),
                                position.coveredQuantity(),
                                position.priceUsd(),
                                position.marketValueUsd(),
                                position.priceSource(),
                                position.pricedAt(),
                                position.stalenessSeconds(),
                                position.isLiveQuote(),
                                position.priceIssue(),
                                position.avcoUsd(),
                                position.netAvcoUsd(),
                                position.unrealizedPnlPct(),
                                position.unrealizedPnlUsd(),
                                position.realizedPnlUsd(),
                                position.networkId(),
                                position.walletAddress(),
                                position.issue(),
                                position.valuationModel(),
                                position.valuationUnderlyingSymbol(),
                                position.unsupportedValuationReason(),
                                position.domain(),
                                position.venueId(),
                                position.subAccount(),
                                position.breakEvenUsd(),
                                position.lockedSurplusUsd(),
                                position.incomeReceivedUsd(),
                                position.attributionTargetFamily()
                        ))
                        .toList()
        );
    }

    public RebuildSessionTransactionsResponse toRebuildResponse(
            SessionTransactionsQueryService.RebuildTransactionsView view
    ) {
        return new RebuildSessionTransactionsResponse(
                view.sessionId(),
                view.projectedTransactions(),
                view.message()
        );
    }

    public SessionTransactionsResponse toSessionTransactionsResponse(
            SessionTransactionsQueryService.SessionTransactionsView view
    ) {
        return new SessionTransactionsResponse(
                view.sessionId(),
                view.offset(),
                view.limit(),
                view.totalCount(),
                view.hasMore(),
                view.items().stream()
                        .map(item -> new SessionTransactionsResponse.Item(
                                item.id(),
                                item.sourceType(),
                                item.txHash(),
                                item.networkId(),
                                item.walletAddress(),
                                item.matchedCounterparty(),
                                item.blockTimestamp(),
                                item.type(),
                                item.status(),
                                item.issue(),
                                item.bridgeStatus(),
                                item.realisedPnlUsdTotal(),
                                item.avcoSnapshotVersion(),
                                item.flows().stream()
                                        .map(flow -> new SessionTransactionsResponse.Flow(
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
                        .toList()
        );
    }
}
