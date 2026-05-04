package com.walletradar.ingestion.job.normalization;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationRequestedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.job.clarification.ClarificationBatchDrainer;
import com.walletradar.ingestion.job.clarification.OnChainClarificationService;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
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
 * Dedicated stage that re-runs on-chain classification after clarification persisted evidence.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OnChainReclassificationJob {

    private static final String STAGE_NAME = "on-chain-reclassification";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainNormalizationProperties properties;
    private final OnChainReclassificationService onChainReclassificationService;
    private final OnChainClarificationService onChainClarificationService;
    private final ClarificationBatchDrainer batchDrainer;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runReclassification() {
        return runReclassification("manual", null, false);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onOnChainClarificationCompleted(OnChainClarificationCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        String trigger = "post-reclassification-fluid-recovery".equals(event.trigger())
                ? event.trigger()
                : "on-chain-clarification-completed";
        runReclassification(trigger, event.sessionId(), true);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onOnChainReclassificationRequested(OnChainReclassificationRequestedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runReclassification(event.trigger(), event.sessionId(), true);
    }

    private int runReclassification(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainReclassificationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        final Instant[] lastHeartbeatAt = {Instant.now()};
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                    "On-chain reclassification running"
            );
            processed += batchDrainer.drain(
                    onChainReclassificationService::processNextBatch,
                    batchProcessed -> lastHeartbeatAt[0] = maybeHeartbeat(sessionId, lastHeartbeatAt[0])
            );
            sessionPipelineStateService.markStageComplete(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                    "On-chain reclassification complete"
            );
            int postReclassificationClarification = runPostReclassificationClarification(processed, trigger);
            if (postReclassificationClarification > 0) {
                applicationEventPublisher.publishEvent(new OnChainClarificationCompletedEvent(
                        sessionId,
                        postReclassificationClarification,
                        "post-reclassification-fluid-recovery"
                ));
                return processed;
            }
            publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
            return processed;
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION);
            running.set(false);
        }
    }

    private int runPostReclassificationClarification(int processed, String trigger) {
        if (processed <= 0 || "post-reclassification-fluid-recovery".equals(trigger)) {
            return 0;
        }
        int clarified = onChainClarificationService.processConfirmedFluidReceiptBatch();
        log.info("Post-reclassification Fluid receipt recovery: processed={}", clarified);
        return clarified;
    }

    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.ON_CHAIN_RECLASSIFICATION,
                "On-chain reclassification running"
        );
        return now;
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainReclassificationCompletedEvent(sessionId, processed, trigger));
    }
}
