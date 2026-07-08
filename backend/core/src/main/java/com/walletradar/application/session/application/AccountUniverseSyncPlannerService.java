package com.walletradar.application.session.application;

import com.walletradar.application.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.application.costbasis.domain.OnChainBalanceRepository;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.application.backfill.job.BackfillJobPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Control-plane planner for session universe changes: reconciles the accounting universe,
 * clears derived session outputs, plans sync windows, and persists backfill segments.
 * Call from {@link AccountUniverseSyncPlanScheduler} (or jobs), not from HTTP request threads —
 * planning performs RPC / explorer head resolution and can be slow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountUniverseSyncPlannerService {

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseSyncService accountingUniverseSyncService;
    private final SourceSyncPlanner sourceSyncPlanner;
    private final BackfillJobPlanner backfillJobPlanner;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
    private final OnChainBalanceRepository onChainBalanceRepository;
    private final SessionPipelineStateService sessionPipelineStateService;

    public void sync(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sync(sessionId.trim(), Instant.now());
    }

    public void sync(String sessionId, Instant observedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String normalizedSessionId = sessionId.trim();
        Instant anchor = observedAt == null ? Instant.now() : observedAt;
        userSessionRepository.findById(normalizedSessionId).ifPresent(session -> doSync(session, anchor));
    }

    private void doSync(UserSession session, Instant observedAt) {
        try {
            accountingUniverseSyncService.sync(session, observedAt);
            clearDerivedState(session);
            SourceSyncPlanner.PlanResult planResult = sourceSyncPlanner.planUniverseChange(session, observedAt);
            backfillJobPlanner.planScheduledSessionSources(
                    session,
                    planResult.scheduledOnChainSyncStatusIds(),
                    planResult.scheduledIntegrationSyncStatusIds()
            );
            session.setUpdatedAt(observedAt);
            SessionWriteMergeSupport.refreshIntegrationsFromDatabase(userSessionRepository, session.getId(), session);
            userSessionRepository.save(session);

            if (planResult.scheduledTargets() > 0) {
                sessionPipelineStateService.markStageRunning(
                        session.getId(),
                        UserSession.PipelineStage.BACKFILL,
                        "Raw backfill started"
                );
            } else {
                sessionPipelineStateService.markStageComplete(
                        session.getId(),
                        UserSession.PipelineStage.BACKFILL,
                        targetCount(session) == 0 ? "Empty session created" : "Raw backfill not required"
                );
            }
            log.info(
                    "Account universe sync planned: sessionId={}, scheduledTargets={}, skippedTargets={}",
                    session.getId(),
                    planResult.scheduledTargets(),
                    planResult.skippedTargets()
            );
        } catch (Exception exception) {
            log.error("Account universe sync planning failed: sessionId={}", session.getId(), exception);
            sessionPipelineStateService.markStageFailed(
                    session.getId(),
                    UserSession.PipelineStage.BACKFILL,
                    "Sync planning failed: " + exception.getMessage()
            );
            throw exception;
        }
    }

    private void clearDerivedState(UserSession session) {
        if (session.getAccountingUniverseId() != null && !session.getAccountingUniverseId().isBlank()) {
            assetLedgerPointRepository.deleteAllByAccountingUniverseId(session.getAccountingUniverseId());
        }
        if (session.getId() != null && !session.getId().isBlank()) {
            onChainBalanceRepository.deleteAllBySessionId(session.getId());
        }
    }

    private int targetCount(UserSession session) {
        int walletTargets = session.getWallets() == null ? 0 : session.getWallets().stream()
                .mapToInt(wallet -> wallet.getNetworks() == null ? 0 : wallet.getNetworks().size())
                .sum();
        int integrationTargets = session.getIntegrations() == null ? 0 : (int) session.getIntegrations().stream()
                .filter(integration -> integration != null
                        && integration.getStatus() != UserSession.IntegrationStatus.DISABLED
                        && integration.getIntegrationId() != null
                        && !integration.getIntegrationId().isBlank())
                .count();
        return walletTargets + integrationTargets;
    }
}
