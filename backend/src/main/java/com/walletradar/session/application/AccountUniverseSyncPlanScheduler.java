package com.walletradar.session.application;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.session.UserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Defers {@link AccountUniverseSyncPlannerService} so session HTTP handlers stay fast: persist first,
 * then plan sync windows and backfill segments on a dedicated pool.
 *
 * <p>Planning for the same {@code sessionId} is serialized: concurrent schedules (e.g. save wallets + Bybit)
 * must not interleave {@code planUniverseChange}, which otherwise creates duplicate {@code sync_status} rows.</p>
 */
@Service
@Slf4j
public class AccountUniverseSyncPlanScheduler {

    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private final AccountUniverseSyncPlannerService accountUniverseSyncPlannerService;
    private final SessionPipelineStateService sessionPipelineStateService;
    private final Executor universeSyncPlanExecutor;

    public AccountUniverseSyncPlanScheduler(
            AccountUniverseSyncPlannerService accountUniverseSyncPlannerService,
            SessionPipelineStateService sessionPipelineStateService,
            @Qualifier(AsyncConfig.UNIVERSE_SYNC_PLAN_EXECUTOR) Executor universeSyncPlanExecutor
    ) {
        this.accountUniverseSyncPlannerService = accountUniverseSyncPlannerService;
        this.sessionPipelineStateService = sessionPipelineStateService;
        this.universeSyncPlanExecutor = universeSyncPlanExecutor;
    }

    /**
     * @param observedAt anchor time for windows (typically the mutation's {@code updatedAt})
     */
    public void schedule(String sessionId, Instant observedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String id = sessionId.trim();
        Instant anchor = observedAt == null ? Instant.now() : observedAt;
        universeSyncPlanExecutor.execute(() -> runPlan(id, anchor));
    }

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private void runPlan(String sessionId, Instant observedAt) {
        synchronized (lockForSession(sessionId)) {
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.BACKFILL,
                    "Universe sync planning…"
            );
            try {
                accountUniverseSyncPlannerService.sync(sessionId, observedAt);
            } catch (RuntimeException e) {
                log.error("Background universe sync planning failed: sessionId={}", sessionId, e);
            }
        }
    }
}
