package com.walletradar.api.controller;

import com.walletradar.api.dto.AddSessionRequest;
import com.walletradar.api.dto.AddSessionResponse;
import com.walletradar.api.dto.DeleteIntegrationResponse;
import com.walletradar.api.dto.PutSessionSettingsRequest;
import com.walletradar.api.dto.RebuildSessionTransactionsResponse;
import com.walletradar.api.dto.SessionBackfillStatusResponse;
import com.walletradar.api.dto.SessionDashboardResponse;
import com.walletradar.api.dto.SessionRefreshResponse;
import com.walletradar.api.dto.SessionSettingsResponse;
import com.walletradar.api.dto.SessionTransactionsResponse;
import com.walletradar.api.dto.SessionResponse;
import com.walletradar.api.dto.UpsertBybitIntegrationRequest;
import com.walletradar.api.dto.UpsertBybitIntegrationResponse;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.ingestion.wallet.command.SessionCommandService;
import com.walletradar.ingestion.wallet.query.SessionDashboardQueryService;
import com.walletradar.ingestion.wallet.query.SessionQueryService;
import com.walletradar.ingestion.wallet.query.SessionTransactionsQueryService;
import com.walletradar.session.application.SessionIntegrationCommandService;
import com.walletradar.session.application.SessionRefreshCommandService;
import com.walletradar.session.application.SessionSettingsCommandService;
import com.walletradar.session.application.SessionSettingsQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;

/**
 * Session API for multi-wallet add and session-level backfill status polling.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionCommandService sessionCommandService;
    private final SessionQueryService sessionQueryService;
    private final SessionDashboardQueryService sessionDashboardQueryService;
    private final SessionTransactionsQueryService sessionTransactionsQueryService;
    private final SessionSettingsQueryService sessionSettingsQueryService;
    private final SessionSettingsCommandService sessionSettingsCommandService;
    private final SessionIntegrationCommandService sessionIntegrationCommandService;
    private final SessionRefreshCommandService sessionRefreshCommandService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AddSessionResponse addSession(@RequestBody @Valid AddSessionRequest request) {
        var payload = request.wallets().stream()
                .map(w -> new SessionCommandService.SessionWalletPayload(
                        w.address(),
                        w.label(),
                        w.color(),
                        w.networks()))
                .toList();
        SessionCommandService.SessionCommandResult result = sessionCommandService.addSession(request.sessionId(), payload);
        return new AddSessionResponse(result.sessionId(), result.message());
    }

    @GetMapping("/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return sessionQueryService.findSession(normalizedSessionIdOrThrow(sessionId))
                .map(this::toSessionResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @GetMapping("/{sessionId}/backfill-status")
    public SessionBackfillStatusResponse getBackfillStatus(@PathVariable String sessionId) {
        return sessionQueryService.findBackfillStatus(normalizedSessionIdOrThrow(sessionId))
                .map(this::toBackfillStatusResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @PostMapping("/{sessionId}/refresh")
    public Mono<SessionRefreshResponse> refreshSession(@PathVariable String sessionId) {
        String normalizedSessionId = normalizedSessionIdOrThrow(sessionId);
        return Mono.fromCallable(() -> sessionRefreshCommandService.refresh(normalizedSessionId)
                        .map(result -> new SessionRefreshResponse(
                                result.sessionId(),
                                result.status().name(),
                                result.scheduledTargets(),
                                result.skippedTargets(),
                                result.message()
                        ))
                        .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(SessionRefreshCommandService.RefreshConflictException.class,
                        exception -> new ApiConflictException("SESSION_REFRESH_CONFLICT", exception.getMessage()));
    }

    @GetMapping("/{sessionId}/settings")
    public SessionSettingsResponse getSessionSettings(@PathVariable String sessionId) {
        return sessionSettingsQueryService.findSessionSettings(normalizedSessionIdOrThrow(sessionId))
                .map(this::toSessionSettingsResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @PutMapping("/{sessionId}/settings")
    public Mono<SessionSettingsResponse> putSessionSettings(
            @PathVariable String sessionId,
            @RequestBody @Valid PutSessionSettingsRequest request
    ) {
        String normalizedSessionId = normalizedSessionIdOrThrow(sessionId);
        return Mono.fromCallable(() -> sessionSettingsCommandService.overwriteSessionSettings(normalizedSessionId, request)
                        .flatMap(saved -> sessionSettingsQueryService.findSessionSettings(normalizedSessionId))
                        .map(this::toSessionSettingsResponse)
                        .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        exception -> new ApiBadRequestException("INVALID_SETTINGS_REQUEST", exception.getMessage()));
    }

    @PutMapping("/{sessionId}/integrations/bybit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<UpsertBybitIntegrationResponse> upsertBybitIntegration(
            @PathVariable String sessionId,
            @RequestBody @Valid UpsertBybitIntegrationRequest request
    ) {
        String normalizedSessionId = normalizedSessionIdOrThrow(sessionId);
        return Mono.fromCallable(() -> sessionIntegrationCommandService.upsertBybit(
                                normalizedSessionId,
                                request.displayName(),
                                request.apiKey(),
                                request.apiSecret()
                        )
                        .map(result -> new UpsertBybitIntegrationResponse(
                                result.integrationId(),
                                result.provider(),
                                result.status(),
                                result.displayName(),
                                result.accountRef(),
                                result.maskedKey(),
                                result.message()
                        ))
                        .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class, exception -> {
                    if ("Integration not found".equals(exception.getMessage())) {
                        return new ApiNotFoundException("INTEGRATION_NOT_FOUND", exception.getMessage());
                    }
                    return new ApiBadRequestException("INVALID_REQUEST", exception.getMessage());
                });
    }

    @DeleteMapping("/{sessionId}/integrations/{integrationId}")
    public DeleteIntegrationResponse deleteIntegration(
            @PathVariable String sessionId,
            @PathVariable String integrationId
    ) {
        try {
            return sessionIntegrationCommandService.removeIntegration(
                            normalizedSessionIdOrThrow(sessionId),
                            integrationId
                    )
                    .map(result -> new DeleteIntegrationResponse(result.integrationId(), result.message()))
                    .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
        } catch (IllegalArgumentException exception) {
            throw new ApiNotFoundException("INTEGRATION_NOT_FOUND", exception.getMessage());
        }
    }

    @GetMapping("/{sessionId}/dashboard")
    public SessionDashboardResponse getDashboard(@PathVariable String sessionId) {
        return sessionDashboardQueryService.findSessionDashboard(normalizedSessionIdOrThrow(sessionId))
                .map(this::toDashboardResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @PostMapping("/{sessionId}/transactions/rebuild")
    public RebuildSessionTransactionsResponse rebuildSessionTransactions(@PathVariable String sessionId) {
        return sessionTransactionsQueryService.rebuildSessionTransactions(normalizedSessionIdOrThrow(sessionId))
                .map(view -> new RebuildSessionTransactionsResponse(
                        view.sessionId(),
                        view.projectedTransactions(),
                        view.message()
                ))
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @GetMapping("/{sessionId}/transactions")
    public SessionTransactionsResponse getSessionTransactions(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String bridgeStatus,
            @RequestParam(defaultValue = "HIDE_SPAM") String spamFilter,
            @RequestParam(name = "walletId", required = false) List<String> walletIds,
            @RequestParam(name = "networkId", required = false) List<String> networkIds
    ) {
        SessionTransactionsQueryService.TransactionsQuery query;
        try {
            query = SessionTransactionsQueryService.normalizeQuery(
                    limit,
                    offset,
                    search,
                    bridgeStatus,
                    spamFilter,
                    walletIds,
                    parseNetworkIds(networkIds)
            );
        } catch (IllegalArgumentException exception) {
            throw new ApiBadRequestException("INVALID_TRANSACTIONS_QUERY", exception.getMessage());
        }

        return sessionTransactionsQueryService.findSessionTransactions(normalizedSessionIdOrThrow(sessionId), query)
                .map(this::toSessionTransactionsResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    private SessionResponse toSessionResponse(SessionQueryService.SessionView view) {
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

    private SessionBackfillStatusResponse toBackfillStatusResponse(SessionQueryService.SessionBackfillStatusView view) {
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

    private SessionDashboardResponse toDashboardResponse(SessionDashboardQueryService.SessionDashboardView view) {
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
                                position.unrealizedPnlPct(),
                                position.unrealizedPnlUsd(),
                                position.realizedPnlUsd(),
                                position.networkId(),
                                position.walletAddress(),
                                position.issue(),
                                position.valuationModel(),
                                position.valuationUnderlyingSymbol(),
                                position.unsupportedValuationReason()
                ))
                        .toList()
        );
    }

    private SessionSettingsResponse toSessionSettingsResponse(SessionSettingsQueryService.SessionSettingsView view) {
        return new SessionSettingsResponse(
                view.sessionId(),
                view.wallets().stream()
                        .map(wallet -> new SessionSettingsResponse.WalletEntry(
                                wallet.address(),
                                wallet.label(),
                                wallet.color(),
                                wallet.networks()
                        ))
                        .toList(),
                view.integrations().stream()
                        .map(integration -> new SessionSettingsResponse.IntegrationEntry(
                                integration.integrationId(),
                                integration.provider(),
                                integration.status(),
                                integration.displayName(),
                                integration.accountRef(),
                                integration.maskedKey(),
                                integration.readOnly(),
                                integration.capabilities(),
                                integration.lastValidatedAt(),
                                integration.lastSyncAt(),
                                integration.lastError(),
                                integration.totalSegments(),
                                integration.completedSegments(),
                                integration.failedSegments(),
                                integration.progressPct(),
                                integration.streamSync() == null
                                        ? List.of()
                                        : integration.streamSync().stream()
                                        .map(row -> new SessionSettingsResponse.StreamSyncEntry(
                                                row.stream(),
                                                row.lastSegmentCompletedAt(),
                                                row.newestStoredEventAt()
                                        ))
                                        .toList()
                        ))
                        .toList(),
                view.externalVenues() == null
                        ? List.of()
                        : view.externalVenues().stream()
                                .map(venue -> new SessionSettingsResponse.ExternalVenueEntry(
                                        venue.address(),
                                        venue.provider(),
                                        venue.label(),
                                        venue.networks()
                                ))
                                .toList(),
                view.hideSmallAssets(),
                view.showReconciliationWarnings()
        );
    }

    private SessionTransactionsResponse toSessionTransactionsResponse(
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

    private List<NetworkId> parseNetworkIds(List<String> rawNetworkIds) {
        if (rawNetworkIds == null || rawNetworkIds.isEmpty()) {
            return List.of();
        }
        try {
            return rawNetworkIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> NetworkId.valueOf(value.trim().toUpperCase(Locale.ROOT)))
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("networkId contains unsupported value");
        }
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
