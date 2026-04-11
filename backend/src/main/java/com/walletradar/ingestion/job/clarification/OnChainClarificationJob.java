package com.walletradar.ingestion.job.clarification;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
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
 * Event-driven driver for on-chain clarification with metadata and allowlisted full-receipt passes.
 */
@Component
@RequiredArgsConstructor
public class OnChainClarificationJob {

    private static final String STAGE_NAME = "on-chain-clarification";
    private static final Logger log = LoggerFactory.getLogger(OnChainClarificationJob.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainClarificationProperties properties;
    private final OnChainClarificationService onChainClarificationService;
    private final OnChainReceiptClarificationService onChainReceiptClarificationService;
    private final ClarificationBatchDrainer clarificationBatchDrainer;
    private final ProtocolNameEnrichmentService protocolNameEnrichmentService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runClarification() {
        return runClarification("manual", null, false);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onOnChainNormalizationCompleted(OnChainNormalizationCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runClarification("normalization-completed", event.sessionId(), true);
    }

    private int runClarification(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainClarificationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        final Instant[] lastHeartbeatAt = {Instant.now()};
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.ON_CHAIN_CLARIFICATION);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "On-chain clarification running"
            );
            processed += clarificationBatchDrainer.drain(
                    onChainClarificationService::processNextBatch,
                    batchProcessed -> lastHeartbeatAt[0] = maybeHeartbeat(sessionId, lastHeartbeatAt[0])
            );
            if (properties.getFullReceipt().isEnabled()) {
                processed += clarificationBatchDrainer.drain(
                        onChainReceiptClarificationService::processNextBatch,
                        batchProcessed -> lastHeartbeatAt[0] = maybeHeartbeat(sessionId, lastHeartbeatAt[0])
                );
            }
            processed += clarificationBatchDrainer.drain(
                    () -> protocolNameEnrichmentService.processNextBatch(properties.getBatchSize()),
                    batchProcessed -> lastHeartbeatAt[0] = maybeHeartbeat(sessionId, lastHeartbeatAt[0])
            );
            sessionPipelineStateService.markStageComplete(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "On-chain clarification complete"
            );
            publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
            return processed;
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.ON_CHAIN_CLARIFICATION);
            running.set(false);
        }
    }

    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.ON_CHAIN_CLARIFICATION);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                "On-chain clarification running"
        );
        return now;
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainClarificationCompletedEvent(sessionId, processed, trigger));
    }
}
