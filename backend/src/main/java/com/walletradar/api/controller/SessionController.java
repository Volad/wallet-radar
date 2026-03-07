package com.walletradar.api.controller;

import com.walletradar.api.dto.AddSessionRequest;
import com.walletradar.api.dto.AddSessionResponse;
import com.walletradar.api.dto.RebuildSessionTransactionsResponse;
import com.walletradar.api.dto.SessionBackfillStatusResponse;
import com.walletradar.api.dto.SessionResponse;
import com.walletradar.api.dto.SessionTransactionsResponse;
import com.walletradar.ingestion.wallet.command.SessionCommandService;
import com.walletradar.ingestion.wallet.command.SessionTransactionCommandService;
import com.walletradar.ingestion.wallet.query.SessionQueryService;
import com.walletradar.ingestion.wallet.query.SessionTransactionQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SessionTransactionCommandService sessionTransactionCommandService;
    private final SessionTransactionQueryService sessionTransactionQueryService;

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

    @PostMapping("/{sessionId}/transactions/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RebuildSessionTransactionsResponse rebuildTransactions(@PathVariable String sessionId) {
        return sessionTransactionCommandService.rebuildChainTransactions(normalizedSessionIdOrThrow(sessionId))
                .map(result -> new RebuildSessionTransactionsResponse(
                        result.sessionId(),
                        result.projectedTransactions(),
                        result.message()))
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @GetMapping("/{sessionId}/transactions")
    public SessionTransactionsResponse getSessionTransactions(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer limit
    ) {
        return sessionTransactionQueryService.findTransactions(normalizedSessionIdOrThrow(sessionId), limit)
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
                view.overallProgressPct(),
                view.totalTargets(),
                view.completedTargets(),
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

    private SessionTransactionsResponse toSessionTransactionsResponse(SessionTransactionQueryService.SessionTransactionsView view) {
        return new SessionTransactionsResponse(
                view.sessionId(),
                view.items().stream()
                        .map(item -> new SessionTransactionsResponse.SessionTransactionItemResponse(
                                item.id(),
                                item.sourceType(),
                                item.txHash(),
                                item.networkId(),
                                item.walletAddress(),
                                item.blockTimestamp(),
                                item.type(),
                                item.bridgeStatus(),
                                item.realisedPnlUsdTotal(),
                                item.avcoSnapshotVersion(),
                                item.flows().stream()
                                        .map(flow -> new SessionTransactionsResponse.SessionTransactionFlowResponse(
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

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
