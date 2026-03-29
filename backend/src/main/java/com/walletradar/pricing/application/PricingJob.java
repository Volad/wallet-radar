package com.walletradar.pricing.application;

import com.walletradar.pricing.telemetry.PricingLogSupport;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled driver for the pricing stage.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PricingJob {

    private static final String STAGE_NAME = "pricing";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final PricingProperties pricingProperties;
    private final PricingJobService pricingJobService;
    private final PricingDataGateService pricingDataGateService;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;

    @Scheduled(fixedDelayString = "${walletradar.pricing.schedule-interval-ms:120000}")
    public void runScheduled() {
        if (!pricingProperties.isEnabled()) {
            return;
        }
        runPricing("scheduled");
    }

    public int runPricing() {
        return runPricing("manual");
    }

    private int runPricing(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("PricingJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = PricingLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            while (true) {
                int batchProcessed = pricingJobService.processNextBatch();
                processed += batchProcessed;
                if (batchProcessed == 0) {
                    PricingDataGateSnapshot snapshot = pricingDataGateService.snapshot();
                    log.info(
                            "Pricing data gate snapshot: avcoReady={}, pendingPrice={}, pendingClarification={}, blockingNeedsReview={}, excludedNeedsReview={}, unresolvedPrice={}",
                            snapshot.avcoReady(),
                            snapshot.pendingPriceCount(),
                            snapshot.pendingClarificationCount(),
                            snapshot.needsReviewCount(),
                            snapshot.excludedNeedsReviewCount(),
                            snapshot.unresolvedPriceCount()
                    );
                    logPipelineSnapshot();
                    return processed;
                }
            }
        } finally {
            PricingLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
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
