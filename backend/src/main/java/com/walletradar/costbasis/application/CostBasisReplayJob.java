package com.walletradar.costbasis.application;

import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.costbasis.domain.AssetLedgerPointRepository;
import com.walletradar.ingestion.job.support.StageExecutionLogSupport;
import com.walletradar.pricing.application.PricingDataGateService;
import com.walletradar.pricing.application.PricingDataGateSnapshot;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionPipelineStateService;
import com.walletradar.telemetry.PipelineTelemetrySnapshot;
import com.walletradar.telemetry.PipelineTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    private final UserSessionRepository userSessionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final PricingDataGateService pricingDataGateService;
    private final PendingStatQueryService pendingStatQueryService;
    private final StatValidationService statValidationService;
    private final AvcoReplayService avcoReplayService;
    private final OnChainBalanceRefreshService onChainBalanceRefreshService;
    private final AssetLedgerPointRepository assetLedgerPointRepository;
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

        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        int processed = 0;
        try {
            if (sessionId == null || sessionId.isBlank()) {
                for (UserSession session : userSessionRepository.findAll()) {
                    processed += runReplayForSession(trigger, session, forceReplay);
                }
                return processed;
            }
            processed = userSessionRepository.findById(sessionId.trim())
                    .map(session -> runReplayForSession(trigger, session, forceReplay))
                    .orElse(0);
            return processed;
        } catch (RuntimeException error) {
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            running.set(false);
        }
    }

    private int runReplayForSession(String trigger, UserSession session, boolean forceReplay) {
        String sessionId = session.getId();
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.ACCOUNTING_REPLAY,
                "Accounting replay running"
        );

        int statProcessed = 0;
        int replayed = 0;
        int promoted = 0;
        int demoted = 0;
        try {
            while (true) {
                StatValidationOutcome outcome = statValidationService.processNextBatch(
                        properties.getValidationBatchSize(),
                        properties.getRetryDelaySeconds(),
                        scope.memberRefs()
                );
                statProcessed += outcome.processed();
                promoted += outcome.promotedToConfirmed();
                demoted += outcome.demotedToNeedsReview();
                if (outcome.processed() == 0) {
                    break;
                }
            }

            PricingDataGateSnapshot gateSnapshot = pricingDataGateService.snapshot(scope.memberRefs());
            long pendingStatCount = pendingStatQueryService.countPending(scope.memberRefs());
            if (!gateSnapshot.avcoReady() || pendingStatCount > 0L) {
                String blockedMessage = String.format(
                        "Accounting replay blocked: active review or pending stat remains (pendingStat=%d, blockingNeedsReview=%d)",
                        pendingStatCount,
                        gateSnapshot.needsReviewCount()
                );
                log.info(
                        "Costbasis replay gate blocked: sessionId={}, avcoReady={}, pendingStat={}, pendingPrice={}, pendingClarification={}, blockingNeedsReview={}, excludedNeedsReview={}, unresolvedPrice={}",
                        sessionId,
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
                sessionPipelineStateService.markStageBlocked(
                        sessionId,
                        UserSession.PipelineStage.ACCOUNTING_REPLAY,
                        blockedMessage
                );
                return 0;
            }

            boolean shouldReplay = forceReplay
                    || promoted > 0
                    || assetLedgerPointRepository.countByAccountingUniverseId(scope.accountingUniverseId()) == 0L;
            if (shouldReplay) {
                replayed = avcoReplayService.replayConfirmed(scope.accountingUniverseId(), scope.memberRefs());
            } else {
                log.info(
                        "Costbasis replay skipped: sessionId={}, no pending stat rows and universe ledger already materialized",
                        sessionId
                );
            }
            Instant evidenceCapturedAt = Instant.now();
            int refreshedBalances = onChainBalanceRefreshService.refreshCurrentBalances(
                    sessionId,
                    scope.onChainWalletRefs(),
                    evidenceCapturedAt
            );
            log.info(
                    "Costbasis on-chain balance refresh outcome: sessionId={}, refreshed={}",
                    sessionId,
                    refreshedBalances
            );

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
                "Pipeline telemetry snapshot: onChainNormalized={}, bybitNormalized={}, pendingStat={}, unmatchedBybitBridge={}, orphanUtaLeg={}, unresolvedPrice={}, blockingNeedsReview={}, excludedNeedsReview={}, assetLedgerPoints={}",
                snapshot.onChainNormalizedCount(),
                snapshot.bybitNormalizedCount(),
                snapshot.pendingStatCount(),
                snapshot.unmatchedBybitBridgeCount(),
                snapshot.orphanUtaLegCount(),
                snapshot.unresolvedPriceCount(),
                snapshot.needsReviewCount(),
                snapshot.excludedNeedsReviewCount(),
                assetLedgerPointRepository.count()
        );
    }
}
