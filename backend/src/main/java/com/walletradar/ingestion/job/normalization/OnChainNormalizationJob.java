package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
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
 * Event-driven driver for the shell on-chain normalization worker.
 */
@Component
@RequiredArgsConstructor
public class OnChainNormalizationJob {

    private static final String STAGE_NAME = "on-chain-normalization";
    private static final Logger log = LoggerFactory.getLogger(OnChainNormalizationJob.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainNormalizationProperties properties;
    private final OnChainNormalizationService onChainNormalizationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runNormalization() {
        return runNormalization("manual", null, false);
    }

    @EventListener
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
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                    "On-chain normalization running"
            );
            while (true) {
                int batchProcessed = onChainNormalizationService.processNextBatch();
                processed += batchProcessed;
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
            running.set(false);
        }
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainNormalizationCompletedEvent(sessionId, processed, trigger));
    }
}
