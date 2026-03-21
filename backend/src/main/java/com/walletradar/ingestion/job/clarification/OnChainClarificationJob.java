package com.walletradar.ingestion.job.clarification;

import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled driver for bounded low-confidence clarification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OnChainClarificationJob {

    private static final String STAGE_NAME = "on-chain-clarification";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainClarificationProperties properties;
    private final OnChainClarificationService onChainClarificationService;

    @Scheduled(fixedDelayString = "${walletradar.normalization.clarification.schedule-interval-ms:120000}")
    public void runScheduled() {
        if (!properties.isEnabled()) {
            return;
        }
        runClarification("scheduled");
    }

    public int runClarification() {
        return runClarification("manual");
    }

    private int runClarification(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainClarificationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            while (true) {
                int batchProcessed = onChainClarificationService.processNextBatch();
                processed += batchProcessed;
                if (batchProcessed == 0) {
                    return processed;
                }
            }
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }
}
