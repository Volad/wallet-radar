package com.walletradar.ingestion.job.normalization;

import com.walletradar.ingestion.config.OnChainNormalizationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled driver for the shell on-chain normalization worker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OnChainNormalizationJob {

    private static final String STAGE_NAME = "on-chain-normalization";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final OnChainNormalizationProperties properties;
    private final OnChainNormalizationService onChainNormalizationService;

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
                    return processed;
                }
            }
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }
}
