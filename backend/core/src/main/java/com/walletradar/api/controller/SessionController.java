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
import com.walletradar.api.portfolio.SessionPortfolioBffMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.session.application.SessionCommandService;
import com.walletradar.application.portfolio.application.port.SessionDashboardReadPort;
import com.walletradar.application.portfolio.application.port.SessionReadPort;
import com.walletradar.application.portfolio.application.port.SessionTransactionsReadPort;
import com.walletradar.application.session.application.SessionIntegrationCommandService;
import com.walletradar.application.session.application.SessionRefreshCommandService;
import com.walletradar.application.session.application.SessionSettingsCommandService;
import com.walletradar.application.session.application.SessionSettingsQueryService;
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
    private final SessionReadPort sessionReadPort;
    private final SessionDashboardReadPort sessionDashboardReadPort;
    private final SessionTransactionsReadPort sessionTransactionsReadPort;
    private final SessionSettingsQueryService sessionSettingsQueryService;
    private final SessionSettingsCommandService sessionSettingsCommandService;
    private final SessionIntegrationCommandService sessionIntegrationCommandService;
    private final SessionRefreshCommandService sessionRefreshCommandService;
    private final SessionPortfolioBffMapper sessionPortfolioBffMapper;

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
        return sessionReadPort.findSession(normalizedSessionIdOrThrow(sessionId))
                .map(sessionPortfolioBffMapper::toSessionResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @GetMapping("/{sessionId}/backfill-status")
    public SessionBackfillStatusResponse getBackfillStatus(@PathVariable String sessionId) {
        return sessionReadPort.findBackfillStatus(normalizedSessionIdOrThrow(sessionId))
                .map(sessionPortfolioBffMapper::toBackfillStatusResponse)
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
        return sessionDashboardReadPort.findSessionDashboard(normalizedSessionIdOrThrow(sessionId))
                .map(sessionPortfolioBffMapper::toDashboardResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @PostMapping("/{sessionId}/transactions/rebuild")
    public RebuildSessionTransactionsResponse rebuildSessionTransactions(@PathVariable String sessionId) {
        return sessionTransactionsReadPort.rebuildSessionTransactions(normalizedSessionIdOrThrow(sessionId))
                .map(sessionPortfolioBffMapper::toRebuildResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @GetMapping("/{sessionId}/transactions")
    public SessionTransactionsResponse getSessionTransactions(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset,
            @RequestParam(required = false) String search,
            @RequestParam(name = "category", required = false) List<String> categories,
            @RequestParam(name = "walletId", required = false) List<String> walletIds,
            @RequestParam(name = "networkId", required = false) List<String> networkIds
    ) {
        try {
            var query = SessionTransactionsReadPort.normalizeQuery(
                    limit,
                    offset,
                    search,
                    categories,
                    walletIds,
                    parseNetworkIds(networkIds)
            );
            return sessionTransactionsReadPort.findSessionTransactions(normalizedSessionIdOrThrow(sessionId), query)
                    .map(sessionPortfolioBffMapper::toSessionTransactionsResponse)
                    .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
        } catch (IllegalArgumentException exception) {
            throw new ApiBadRequestException("INVALID_TRANSACTIONS_QUERY", exception.getMessage());
        }
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
                                integration.color(),
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

    private List<NetworkId> parseNetworkIds(List<String> rawNetworkIds) {
        if (rawNetworkIds == null || rawNetworkIds.isEmpty()) {
            return List.of();
        }
        return rawNetworkIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> {
                    try {
                        return java.util.stream.Stream.of(NetworkId.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .distinct()
                .toList();
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
