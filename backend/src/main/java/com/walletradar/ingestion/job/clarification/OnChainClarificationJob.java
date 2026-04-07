package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for on-chain clarification with metadata and allowlisted full-receipt passes.
 */
@Component
@RequiredArgsConstructor
public class OnChainClarificationJob {

    private static final String STAGE_NAME = "on-chain-clarification";
    private static final Logger log = LoggerFactory.getLogger(OnChainClarificationJob.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainClarificationProperties properties;
    private final OnChainClarificationService onChainClarificationService;
    private final OnChainReceiptClarificationService onChainReceiptClarificationService;
    private final ClarificationBatchDrainer clarificationBatchDrainer;
    private final ClarificationPostProcessingHandler clarificationPostProcessingHandler;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runClarification() {
        return runClarification("manual", null, false);
    }

    @EventListener
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
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_CLARIFICATION,
                    "On-chain clarification running"
            );
            processed += clarificationBatchDrainer.drain(onChainClarificationService::processNextBatch);
            if (properties.getFullReceipt().isEnabled()) {
                processed += clarificationBatchDrainer.drain(onChainReceiptClarificationService::processNextBatch);
            }
            processed += clarificationPostProcessingHandler.reconcileBridgePairs(Math.max(500, properties.getBatchSize()));
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
            running.set(false);
        }
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainClarificationCompletedEvent(sessionId, processed, trigger));
    }
}
