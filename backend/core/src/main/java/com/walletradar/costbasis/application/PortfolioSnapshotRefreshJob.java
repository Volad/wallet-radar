package com.walletradar.costbasis.application;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.platform.common.job.StageExecutionLogSupport;
import com.walletradar.costbasis.application.port.OnChainBalanceRefresher;
import com.walletradar.pricing.application.CurrentPriceQuoteRefreshService;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refreshes dashboard live-balance and current-quote snapshots after AVCO replay.
 */
@Component
@RequiredArgsConstructor
public class PortfolioSnapshotRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotRefreshJob.class);
    private static final String STAGE_NAME = "portfolio-snapshot-refresh";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /** Minimum gap between on-chain balance refreshes for the same session (avoids redundant RPC storms). */
    private static final Duration SNAPSHOT_MIN_INTERVAL = Duration.ofSeconds(45);

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Tracks when each session's snapshot was last fully refreshed (in-process, non-persistent). */
    private final ConcurrentHashMap<String, Instant> lastRefreshedAt = new ConcurrentHashMap<>();

    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final OnChainBalanceRefresher onChainBalanceRefresher;
    private final CurrentPriceQuoteRefreshService currentPriceQuoteRefreshService;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runSnapshotRefresh() {
        return runSnapshotRefresh("manual", null);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onAccountingReplayCompleted(AccountingReplayCompletedEvent event) {
        if (event == null) {
            return;
        }
        runSnapshotRefresh("accounting-replay-completed", event.sessionId());
    }

    private int runSnapshotRefresh(String trigger, String sessionId) {
        if (!running.compareAndSet(false, true)) {
            log.debug("PortfolioSnapshotRefreshJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        int processed = 0;
        try {
            if (sessionId == null || sessionId.isBlank()) {
                for (UserSession session : userSessionRepository.findAll()) {
                    processed += runSnapshotRefreshForSession(trigger, session);
                }
                return processed;
            }
            processed = userSessionRepository.findById(sessionId.trim())
                    .map(session -> runSnapshotRefreshForSession(trigger, session))
                    .orElse(0);
            return processed;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private int runSnapshotRefreshForSession(String trigger, UserSession session) {
        String sessionId = session.getId();

        // Guard against redundant RPC storms when multiple accounting-replay-completed events
        // fire in quick succession (e.g. when several backfill batches trigger separate pipeline
        // runs). The manual trigger bypasses this check.
        if (!"manual".equals(trigger)) {
            Instant last = lastRefreshedAt.get(sessionId);
            if (last != null && Duration.between(last, Instant.now()).compareTo(SNAPSHOT_MIN_INTERVAL) < 0) {
                log.info(
                        "Portfolio snapshot refresh skipped (TTL): sessionId={}, trigger={}, lastRefreshedAt={}, minIntervalSec={}",
                        sessionId,
                        trigger,
                        last,
                        SNAPSHOT_MIN_INTERVAL.getSeconds()
                );
                return 0;
            }
        }

        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        StageHeartbeat heartbeat = new StageHeartbeat(sessionId);
        sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                "Portfolio snapshot refresh running"
        );

        try {
            Instant evidenceCapturedAt = Instant.now();
            int refreshedBalances = onChainBalanceRefresher.refreshCurrentBalances(
                    sessionId,
                    scope.onChainWalletRefs(),
                    evidenceCapturedAt,
                    heartbeat::pulse
            );
            log.info(
                    "Portfolio snapshot on-chain balance refresh outcome: sessionId={}, refreshed={}",
                    sessionId,
                    refreshedBalances
            );
            int refreshedQuotes = currentPriceQuoteRefreshService.refreshForSessionBalances(sessionId, evidenceCapturedAt);
            log.info(
                    "Portfolio snapshot current quote refresh outcome: sessionId={}, refreshed={}",
                    sessionId,
                    refreshedQuotes
            );
            lastRefreshedAt.put(sessionId, Instant.now());
            sessionPipelineStateService.markStageComplete(
                    sessionId,
                    UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                    "Portfolio snapshot refresh complete"
            );
            return refreshedBalances + refreshedQuotes;
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                    error.getMessage()
            );
            throw error;
        } finally {
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH);
        }
    }

    private void heartbeat(String sessionId) {
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH,
                "Portfolio snapshot refresh running"
        );
    }

    private final class StageHeartbeat {
        private final String sessionId;
        private Instant lastHeartbeatAt = Instant.now();

        private StageHeartbeat(String sessionId) {
            this.sessionId = sessionId;
        }

        private void pulse() {
            Instant now = Instant.now();
            if (Duration.between(lastHeartbeatAt, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
                return;
            }
            heartbeat(sessionId);
            lastHeartbeatAt = now;
        }
    }
}
