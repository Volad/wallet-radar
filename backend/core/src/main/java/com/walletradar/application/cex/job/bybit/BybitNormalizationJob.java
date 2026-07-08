package com.walletradar.application.cex.job.bybit;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.BybitNormalizationRequestedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.application.normalization.config.BybitNormalizationProperties;
import com.walletradar.application.pipeline.job.support.StageExecutionLogSupport;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for Bybit normalization.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitNormalizationJob {

    private static final String STAGE_NAME = "bybit-normalization";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final BybitNormalizationProperties properties;
    private final BybitNormalizationService bybitNormalizationService;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runNormalization() {
        return runNormalization("manual", null, false);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onSessionBackfillCompleted(SessionBackfillCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runNormalization("session-backfill-completed", event.sessionId(), true);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onBybitNormalizationRequested(BybitNormalizationRequestedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runNormalization(event.trigger(), event.sessionId(), true);
    }

    private int runNormalization(String trigger) {
        return runNormalization(trigger, null, false);
    }

    private int runNormalization(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("BybitNormalizationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        Instant lastHeartbeatAt = Instant.now();
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.BYBIT_NORMALIZATION);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.BYBIT_NORMALIZATION,
                    "Bybit normalization running"
            );
            while (true) {
                int batchProcessed = bybitNormalizationService.processNextBatch(properties.getBatchSize(), sessionId);
                processed += batchProcessed;
                lastHeartbeatAt = maybeHeartbeat(sessionId, UserSession.PipelineStage.BYBIT_NORMALIZATION, "Bybit normalization running", lastHeartbeatAt);
                if (batchProcessed == 0) {
                    logPipelineSnapshot();
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.BYBIT_NORMALIZATION,
                            "Bybit normalization complete"
                    );
                    publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
                    return processed;
                }
            }
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.BYBIT_NORMALIZATION,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.BYBIT_NORMALIZATION);
            running.set(false);
        }
    }

    private Instant maybeHeartbeat(
            String sessionId,
            UserSession.PipelineStage stage,
            String message,
            Instant lastHeartbeatAt
    ) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, stage);
        sessionPipelineStateService.markStageRunning(sessionId, stage, message);
        return now;
    }

    private void logPipelineSnapshot() {
        PipelineTelemetrySnapshot snapshot = pipelineTelemetrySnapshotService.snapshot();
        log.info(
                "Pipeline telemetry snapshot: onChainNormalized={}, bybitNormalized={}, pendingStat={}, unmatchedBybitBridge={}, orphanUtaLeg={}, unresolvedPrice={}, blockingNeedsReview={}, excludedNeedsReview={}",
                snapshot.onChainNormalizedCount(),
                snapshot.bybitNormalizedCount(),
                snapshot.pendingStatCount(),
                snapshot.unmatchedBybitBridgeCount(),
                snapshot.orphanUtaLegCount(),
                snapshot.unresolvedPriceCount(),
                snapshot.needsReviewCount(),
                snapshot.excludedNeedsReviewCount()
        );
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new BybitNormalizationCompletedEvent(sessionId, processed, trigger));
    }
}
