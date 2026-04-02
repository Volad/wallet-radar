package com.walletradar.costbasis.application;

import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.costbasis.domain.AssetPositionRepository;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import com.walletradar.pricing.application.PricingDataGateService;
import com.walletradar.pricing.application.PricingDataGateSnapshot;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event-driven driver for stat validation and deterministic AVCO replay.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CostBasisReplayJob {

    private static final String STAGE_NAME = "costbasis-replay";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final CostBasisProperties properties;
    private final PricingDataGateService pricingDataGateService;
    private final PendingStatQueryService pendingStatQueryService;
    private final StatValidationService statValidationService;
    private final AvcoReplayService avcoReplayService;
    private final AssetPositionRepository assetPositionRepository;
    private final PipelineTelemetrySnapshotService pipelineTelemetrySnapshotService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runReplay() {
        return runReplay("manual", null, true);
    }

    @EventListener
    public void onPricingCompleted(PricingCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runReplay("pricing-completed", event.sessionId(), true);
    }

    private int runReplay(String trigger, String sessionId, boolean forceReplay) {
        if (!running.compareAndSet(false, true)) {
            log.debug("CostBasisReplayJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int statProcessed = 0;
        int replayed = 0;
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    "Accounting replay running"
            );
            int promoted = 0;
            int demoted = 0;
            while (true) {
                StatValidationOutcome outcome = statValidationService.processNextBatch(
                        properties.getValidationBatchSize(),
                        properties.getRetryDelaySeconds()
                );
                statProcessed += outcome.processed();
                promoted += outcome.promotedToConfirmed();
                demoted += outcome.demotedToNeedsReview();
                if (outcome.processed() == 0) {
                    break;
                }
            }

            PricingDataGateSnapshot gateSnapshot = pricingDataGateService.snapshot();
            long pendingStatCount = pendingStatQueryService.countPending();
            if (!gateSnapshot.avcoReady() || pendingStatCount > 0L) {
                log.info(
                        "Costbasis replay gate blocked: avcoReady={}, pendingStat={}, pendingPrice={}, pendingClarification={}, blockingNeedsReview={}, excludedNeedsReview={}, unresolvedPrice={}",
                        gateSnapshot.avcoReady(),
                        pendingStatCount,
                        gateSnapshot.pendingPriceCount(),
                        gateSnapshot.pendingClarificationCount(),
                        gateSnapshot.needsReviewCount(),
                        gateSnapshot.excludedNeedsReviewCount(),
                        gateSnapshot.unresolvedPriceCount()
                );
                logStatOutcome(promoted, demoted, statProcessed);
                logSnapshot();
                sessionPipelineStateService.markStageComplete(
                        sessionId,
                        UserSession.PipelineStage.ACCOUNTING_REPLAY,
                        "Accounting replay complete"
                );
                return 0;
            }

            boolean shouldReplay = forceReplay || promoted > 0 || assetPositionRepository.count() == 0L;
            if (shouldReplay) {
                replayed = avcoReplayService.replayConfirmed();
            } else {
                log.info("Costbasis replay skipped: no pending stat rows and asset_positions already materialized");
            }

            logStatOutcome(promoted, demoted, statProcessed);
            logSnapshot();
            sessionPipelineStateService.markStageComplete(
                    sessionId,
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    "Accounting replay complete"
            );
            return replayed;
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.ACCOUNTING_REPLAY,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, statProcessed + replayed, startedAtNanos);
            running.set(false);
        }
    }

    private void logStatOutcome(int promoted, int demoted, int processed) {
        log.info(
                "Costbasis stat validation outcome: processed={}, promotedToConfirmed={}, demotedToNeedsReview={}",
                processed,
                promoted,
                demoted
        );
    }

    private void logSnapshot() {
        PipelineTelemetrySnapshot snapshot = pipelineTelemetrySnapshotService.snapshot();
        log.info(
                "Pipeline telemetry snapshot: onChainNormalized={}, bybitNormalized={}, pendingStat={}, unmatchedBybitBridge={}, orphanUtaLeg={}, unresolvedPrice={}, blockingNeedsReview={}, excludedNeedsReview={}, assetPositions={}",
                snapshot.onChainNormalizedCount(),
                snapshot.bybitNormalizedCount(),
                snapshot.pendingStatCount(),
                snapshot.unmatchedBybitBridgeCount(),
                snapshot.orphanUtaLegCount(),
                snapshot.unresolvedPriceCount(),
                snapshot.needsReviewCount(),
                snapshot.excludedNeedsReviewCount(),
                assetPositionRepository.count()
        );
    }
}
