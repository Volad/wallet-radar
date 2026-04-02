package com.walletradar.api.controller;

import com.walletradar.api.dto.AddSessionRequest;
import com.walletradar.api.dto.AddSessionResponse;
import com.walletradar.api.dto.SessionBackfillStatusResponse;
import com.walletradar.api.dto.SessionResponse;
import com.walletradar.ingestion.wallet.command.SessionCommandService;
import com.walletradar.ingestion.wallet.query.SessionQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session API for multi-wallet add and session-level backfill status polling.
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionCommandService sessionCommandService;
    private final SessionQueryService sessionQueryService;

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
                view.overallProgressPct(),
                view.totalTargets(),
                view.completedTargets(),
                view.pipelineStage(),
                view.pipelineStatus(),
                view.pipelineMessage(),
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

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
