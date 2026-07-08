package com.walletradar.application.pricing.application;

import com.walletradar.platform.common.config.AsyncConfig;
import com.walletradar.domain.event.LinkingCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.PricingRequestedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.application.pipeline.config.JobHeartbeatProperties;
import com.walletradar.application.pricing.telemetry.PricingLogSupport;
import com.walletradar.application.session.application.SessionPipelineActivityService;
import com.walletradar.application.session.application.SessionPipelineStateService;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.platform.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for the pricing stage.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PricingJob {

    private static final String STAGE_NAME = "pricing";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final JobHeartbeatProperties jobHeartbeatProperties;
    private final PricingProperties pricingProperties;
    private final PricingJobService pricingJobService;
    private final PricingDataGateService pricingDataGateService;
    private final StalePriceUnresolvedRepairService stalePriceUnresolvedRepairService;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runPricing() {
        return runPricing("manual", null, false);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onLinkingCompleted(LinkingCompletedEvent event) {
        if (!pricingProperties.isEnabled() || event == null) {
            return;
        }
        runPricing("linking-completed", event.sessionId(), true);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onPricingRequested(PricingRequestedEvent event) {
        if (!pricingProperties.isEnabled() || event == null) {
            return;
        }
        runPricing(event.trigger(), event.sessionId(), true);
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
        Instant[] lastHeartbeatAtHolder = {Instant.now()};
        long startedAtNanos = PricingLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.PRICING);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.PRICING,
                    "Pricing running"
            );
            while (true) {
                int batchProcessed = pricingJobService.processNextBatch(
                        () -> lastHeartbeatAtHolder[0] = maybeHeartbeat(sessionId, lastHeartbeatAtHolder[0])
                );
                processed += batchProcessed;
                lastHeartbeatAtHolder[0] = maybeHeartbeat(sessionId, lastHeartbeatAtHolder[0]);
                if (batchProcessed == 0) {
                    int repaired = repairStalePriceReasons();
                    if (repaired > 0) {
                        log.info("Stale unresolved-price cleanup repaired={} rows", repaired);
                    }
                    PricingDataGateSnapshot snapshot = pricingDataGateService.snapshot();
                    log.info(
                            "Pricing data gate snapshot: avcoReady={}, pendingPrice={}, pendingClarification={}, pendingReclassification={}, blockingNeedsReview={}, excludedNeedsReview={}, unresolvedPrice={}",
                            snapshot.avcoReady(),
                            snapshot.pendingPriceCount(),
                            snapshot.pendingClarificationCount(),
                            snapshot.pendingReclassificationCount(),
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
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.PRICING);
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


    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(jobHeartbeatProperties.heartbeatInterval()) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.PRICING);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.PRICING,
                "Pricing running"
        );
        return now;
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
