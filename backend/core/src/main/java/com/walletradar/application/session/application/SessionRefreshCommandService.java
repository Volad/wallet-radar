package com.walletradar.application.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.backfill.job.BackfillJobPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Schedules a bounded refresh cycle without clearing historical raw or
 * canonical history. The same sync_status rows are reused; only orchestration
 * segments are replaced for sources that actually have a delta window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionRefreshCommandService {

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final SourceSyncPlanner sourceSyncPlanner;
    private final BackfillJobPlanner backfillJobPlanner;
    private final SessionPipelineStateService sessionPipelineStateService;

    public Optional<SessionRefreshResult> refresh(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::scheduleRefresh);
    }

    private SessionRefreshResult scheduleRefresh(UserSession session) {
        ensureRefreshAllowed(session);

        Instant now = Instant.now();
        SourceSyncPlanner.PlanResult planResult = sourceSyncPlanner.planRefresh(session, now);

        if (planResult.scheduledTargets() <= 0) {
            return new SessionRefreshResult(
                    session.getId(),
                    RefreshStatus.UP_TO_DATE,
                    0,
                    planResult.skippedTargets(),
                    "Session is already up to date"
            );
        }

        backfillJobPlanner.planScheduledSessionSources(
                session,
                planResult.scheduledOnChainSyncStatusIds(),
                planResult.scheduledIntegrationSyncStatusIds()
        );
        session.setUpdatedAt(now);
        SessionWriteMergeSupport.refreshIntegrationsFromDatabase(userSessionRepository, session.getId(), session);
        userSessionRepository.save(session);
        sessionPipelineStateService.markStageRunning(
                session.getId(),
                UserSession.PipelineStage.BACKFILL,
                "Incremental refresh queued"
        );
        log.info(
                "Session refresh scheduled: sessionId={}, scheduledTargets={}, skippedTargets={}",
                session.getId(),
                planResult.scheduledTargets(),
                planResult.skippedTargets()
        );
        return new SessionRefreshResult(
                session.getId(),
                RefreshStatus.SCHEDULED,
                planResult.scheduledTargets(),
                planResult.skippedTargets(),
                "Incremental refresh queued"
        );
    }

    private void ensureRefreshAllowed(UserSession session) {
        if (session == null || session.getId() == null) {
            throw new RefreshConflictException("Session refresh requires a persisted session");
        }
        UserSession.PipelineState pipelineState = session.getPipelineState();
        if (pipelineState != null && pipelineState.getStatus() == UserSession.PipelineStatus.RUNNING) {
            throw new RefreshConflictException("Refresh is unavailable while the pipeline is running");
        }
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                                SyncStatus.SourceKind.ONCHAIN,
                                wallet.getAddress(),
                                networkId.name()
                        )
                        .orElse(null);
                if (status == null || !status.isBackfillComplete()) {
                    throw new RefreshConflictException("Refresh is unavailable until the initial backfill is complete");
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            SyncStatus latest = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
            boolean complete = latest != null
                    ? latest.isBackfillComplete()
                    : integration.getStatus() == UserSession.IntegrationStatus.READY;
            if (!complete) {
                throw new RefreshConflictException("Refresh is unavailable until the initial integration backfill is complete");
            }
        }
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .filter(integration -> integration.getIntegrationId() != null && !integration.getIntegrationId().isBlank())
                .toList();
    }

    public record SessionRefreshResult(
            String sessionId,
            RefreshStatus status,
            int scheduledTargets,
            int skippedTargets,
            String message
    ) {
    }

    public enum RefreshStatus {
        SCHEDULED,
        UP_TO_DATE
    }

    public static class RefreshConflictException extends RuntimeException {
        public RefreshConflictException(String message) {
            super(message);
        }
    }
}
