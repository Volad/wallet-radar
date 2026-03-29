package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public int runClarification() {
        return runClarification("manual");
    }

    @EventListener
    public void onOnChainNormalizationCompleted(OnChainNormalizationCompletedEvent event) {
        if (!properties.isEnabled() || event == null || event.processed() <= 0) {
            return;
        }
        runClarification("normalization-completed");
    }

    private int runClarification(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("OnChainClarificationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            processed += drainMetadataClarification();
            if (properties.getFullReceipt().isEnabled()) {
                processed += drainFullReceiptClarification();
            }
            return processed;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private int drainMetadataClarification() {
        int processed = 0;
        while (true) {
            int batchProcessed = onChainClarificationService.processNextBatch();
            processed += batchProcessed;
            if (batchProcessed == 0) {
                return processed;
            }
        }
    }

    private int drainFullReceiptClarification() {
        int processed = 0;
        while (true) {
            int batchProcessed = onChainReceiptClarificationService.processNextBatch();
            processed += batchProcessed;
            if (batchProcessed == 0) {
                return processed;
            }
        }
    }
}
