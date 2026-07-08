package com.walletradar.application.normalization.job;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.pipeline.job.support.StageExecutionLogSupport;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for the shell on-chain normalization worker.
 */
@Component
@RequiredArgsConstructor
public class OnChainNormalizationJob {

    private static final String STAGE_NAME = "on-chain-normalization";
    private static final Logger log = LoggerFactory.getLogger(OnChainNormalizationJob.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainNormalizationProperties properties;
    private final OnChainNormalizationService onChainNormalizationService;
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
        runNormalization("session-backfill-completed:" + event.sessionId(), event.sessionId(), true);
    }

    private int runNormalization(String trigger) {
        return runNormalization(trigger, null, false);
    }

    private int runNormalization(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainNormalizationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        Instant lastHeartbeatAt = Instant.now();
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    "On-chain normalization running"
            );
            while (true) {
                int batchProcessed = onChainNormalizationService.processNextBatch(sessionId);
                processed += batchProcessed;
                lastHeartbeatAt = maybeHeartbeat(sessionId, UserSession.PipelineStage.ON_CHAIN_NORMALIZATION, "On-chain normalization running", lastHeartbeatAt);
                if (batchProcessed == 0) {
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                            "On-chain normalization complete"
                    );
                    publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
                    return processed;
                }
            }
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
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

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainNormalizationCompletedEvent(sessionId, processed, trigger));
    }
}
