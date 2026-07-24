package com.walletradar.application.normalization.job;

import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.pipeline.ton.TonNormalizationService;
import com.walletradar.application.pipeline.config.JobHeartbeatProperties;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import com.walletradar.application.session.application.SessionPipelineStateService;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.platform.common.config.AsyncConfig;
import com.walletradar.platform.common.job.StageExecutionLogSupport;
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
 * Event-driven driver for TON normalization.
 *
 * <p>Subscribes to {@link SessionBackfillCompletedEvent} (same as the EVM on-chain job) and
 * drains pending TON raw transactions in a single-flight loop. Publishes
 * {@link OnChainNormalizationCompletedEvent} on completion so downstream pipeline stages
 * (linking, pricing, AVCO) trigger correctly.</p>
 */
@Component
@RequiredArgsConstructor
public class TonNormalizationJob {

    private static final String STAGE_NAME = "ton-normalization";
    private static final Logger log = LoggerFactory.getLogger(TonNormalizationJob.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final JobHeartbeatProperties jobHeartbeatProperties;
    private final OnChainNormalizationProperties properties;
    private final TonNormalizationService tonNormalizationService;
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

    private int runNormalization(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("TonNormalizationJob skipped: already running, trigger={}", trigger);
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
                    "TON normalization running"
            );
            while (true) {
                int batchProcessed = tonNormalizationService.processNextBatch(sessionId);
                processed += batchProcessed;
                lastHeartbeatAt = maybeHeartbeat(sessionId, lastHeartbeatAt);
                if (batchProcessed == 0) {
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                            "TON normalization complete"
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

    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(jobHeartbeatProperties.heartbeatInterval()) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        sessionPipelineStateService.markStageRunning(sessionId, UserSession.PipelineStage.ON_CHAIN_NORMALIZATION, "TON normalization running");
        return now;
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainNormalizationCompletedEvent(sessionId, processed, trigger));
    }
}
