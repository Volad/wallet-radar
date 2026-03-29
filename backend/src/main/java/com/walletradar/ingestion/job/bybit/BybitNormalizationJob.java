package com.walletradar.ingestion.job.bybit;

import com.walletradar.ingestion.config.BybitNormalizationProperties;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled driver for Bybit normalization.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BybitNormalizationJob {

    private static final String STAGE_NAME = "bybit-normalization";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final BybitNormalizationProperties properties;
    private final BybitNormalizationService bybitNormalizationService;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Scheduled(fixedDelayString = "${walletradar.normalization.bybit.schedule-interval-ms:90000}")
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
            log.debug("BybitNormalizationJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            while (true) {
                int batchProcessed = bybitNormalizationService.processNextBatch(properties.getBatchSize());
                processed += batchProcessed;
                if (batchProcessed == 0) {
                    logPipelineSnapshot();
                    return processed;
                }
            }
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private void logPipelineSnapshot() {
        PipelineTelemetrySnapshot snapshot = pipelineTelemetrySnapshotService.snapshot();
        log.info(
                "Pipeline telemetry snapshot: onChainNormalized={}, bybitNormalized={}, pendingStat={}, unmatchedBybitBridge={}, orphanUtaLeg={}, unresolvedPrice={}, blockingNeedsReview={}, excludedNeedsReview={}",
                snapshot.onChainNormalizedCount(),
                snapshot.bybitNormalizedCount(),
                snapshot.pendingStatCount(),
                snapshot.unmatchedBybitBridgeCount(),
                snapshot.orphanUtaLegCount(),
                snapshot.unresolvedPriceCount(),
                snapshot.needsReviewCount(),
                snapshot.excludedNeedsReviewCount()
        );
    }
}
