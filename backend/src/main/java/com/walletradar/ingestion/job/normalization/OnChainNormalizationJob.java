package com.walletradar.ingestion.job.normalization;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled driver for the shell on-chain normalization worker.
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

    @Scheduled(fixedDelayString = "${walletradar.normalization.on-chain.schedule-interval-ms:90000}")
    public void runScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        runNormalization("scheduled");
    }

    public int runNormalization() {
        return runNormalization("manual");
    }

    private int runNormalization(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainNormalizationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            while (true) {
                int batchProcessed = onChainNormalizationService.processNextBatch();
                processed += batchProcessed;
                if (batchProcessed == 0) {
                    publishCompletionEvent(processed, trigger);
                    return processed;
                }
            }
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private void publishCompletionEvent(int processed, String trigger) {
        if (processed <= 0) {
            return;
        }
        applicationEventPublisher.publishEvent(new OnChainNormalizationCompletedEvent(processed, trigger));
    }
}
