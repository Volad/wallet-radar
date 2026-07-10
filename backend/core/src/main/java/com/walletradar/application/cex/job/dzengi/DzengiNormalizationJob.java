package com.walletradar.application.cex.job.dzengi;

import com.walletradar.platform.common.config.AsyncConfig;
import com.walletradar.domain.event.DzengiNormalizationCompletedEvent;
import com.walletradar.domain.event.DzengiNormalizationRequestedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.application.normalization.config.DzengiNormalizationProperties;
import com.walletradar.application.pipeline.config.JobHeartbeatProperties;
import com.walletradar.platform.common.job.StageExecutionLogSupport;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import com.walletradar.application.session.application.SessionPipelineStateService;
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
 * Event-driven driver for Dzengi normalization.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DzengiNormalizationJob {

    private static final String STAGE_NAME = "dzengi-normalization";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final JobHeartbeatProperties jobHeartbeatProperties;
    private final DzengiNormalizationProperties properties;
    private final DzengiNormalizationService dzengiNormalizationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

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
    public void onDzengiNormalizationRequested(DzengiNormalizationRequestedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runNormalization(event.trigger(), event.sessionId(), true);
    }

    public int runNormalization() {
        return runNormalization("manual", null, false);
    }

    private int runNormalization(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("DzengiNormalizationJob skipped: already running, trigger={}", trigger);
            return 0;
        }
        int processed = 0;
        Instant lastHeartbeatAt = Instant.now();
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.DZENGI_NORMALIZATION);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.DZENGI_NORMALIZATION,
                    "Dzengi normalization running"
            );
            while (true) {
                int batchProcessed = dzengiNormalizationService.processNextBatch(properties.getBatchSize(), sessionId);
                processed += batchProcessed;
                lastHeartbeatAt = maybeHeartbeat(sessionId, lastHeartbeatAt);
                if (batchProcessed == 0) {
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.DZENGI_NORMALIZATION,
                            "Dzengi normalization complete"
                    );
                    publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
                    return processed;
                }
            }
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.DZENGI_NORMALIZATION,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.DZENGI_NORMALIZATION);
            running.set(false);
        }
    }

    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(jobHeartbeatProperties.heartbeatInterval()) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.DZENGI_NORMALIZATION);
        sessionPipelineStateService.markStageRunning(sessionId, UserSession.PipelineStage.DZENGI_NORMALIZATION, "Dzengi normalization running");
        return now;
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new DzengiNormalizationCompletedEvent(sessionId, processed, trigger));
    }
}
