package com.walletradar.pricing.application;

import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.pricing.telemetry.PricingLogSupport;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for the pricing stage.
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
    private final StalePriceUnresolvedRepairService stalePriceUnresolvedRepairService;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runPricing() {
        return runPricing("manual", null, false);
    }

    @EventListener
    public void onBybitNormalizationCompleted(BybitNormalizationCompletedEvent event) {
        if (!pricingProperties.isEnabled() || event == null) {
            return;
        }
        runPricing("bybit-normalization-completed", event.sessionId(), true);
    }

    private int runPricing(String trigger) {
        return runPricing(trigger, null, false);
    }

    private int runPricing(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("PricingJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        long startedAtNanos = PricingLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.PRICING,
                    "Pricing running"
            );
            while (true) {
                int batchProcessed = pricingJobService.processNextBatch();
                processed += batchProcessed;
                if (batchProcessed == 0) {
                    int repaired = repairStalePriceReasons();
                    if (repaired > 0) {
                        log.info("Stale unresolved-price cleanup repaired={} rows", repaired);
                    }
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
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.PRICING,
                            "Pricing complete"
                    );
                    publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
                    return processed;
                }
            }
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.PRICING,
                    error.getMessage()
            );
            throw error;
        } finally {
            PricingLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private int repairStalePriceReasons() {
        int repaired = 0;
        while (true) {
            int batch = stalePriceUnresolvedRepairService.repairNextBatch(pricingProperties.getBatchSize());
            repaired += batch;
            if (batch == 0) {
                return repaired;
            }
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

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new PricingCompletedEvent(sessionId, processed, trigger));
    }
}
