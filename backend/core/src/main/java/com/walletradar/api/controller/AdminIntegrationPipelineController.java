package com.walletradar.api.controller;

import com.walletradar.application.pipeline.admin.IntegrationPipelineAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

/**
 * Operator-only pipeline controls. Disabled when {@code walletradar.admin.integration-rebuild-token} is unset.
 */
@RestController
@RequestMapping("/api/v1/admin/integrations")
@RequiredArgsConstructor
public class AdminIntegrationPipelineController {

    private final IntegrationPipelineAdminService integrationPipelineAdminService;

    @Value("${walletradar.admin.integration-rebuild-token:}")
    private String configuredRebuildToken;

    /**
     * Bybit-only cold rebuild for one integration (raw + extracted + BYBIT normalized + ledger rows for that UID).
     * Optionally re-arms on-chain {@link com.walletradar.domain.sync.SyncStatus} block windows for wallets on the same session(s).
     *
     * <p>Auth: header {@code X-WalletRadar-Admin-Token} must match the configured token (constant-time compare).</p>
     */
    @PostMapping("/{integrationId}/full-rebuild")
    public Mono<IntegrationPipelineAdminService.FullRebuildBybitResult> fullRebuildBybit(
            @PathVariable("integrationId") String integrationId,
            @RequestHeader(value = "X-WalletRadar-Admin-Token", required = false) String adminToken,
            @RequestParam(name = "repairOnChainWindows", defaultValue = "true") boolean repairOnChainWindows
    ) {
        if (configuredRebuildToken == null || configuredRebuildToken.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Admin integration rebuild is not configured (set walletradar.admin.integration-rebuild-token)"
            ));
        }
        if (!Objects.equals(configuredRebuildToken, adminToken)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-WalletRadar-Admin-Token"));
        }
        return Mono.fromCallable(() -> integrationPipelineAdminService.fullRebuildBybit(integrationId, repairOnChainWindows))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e));
    }

}
