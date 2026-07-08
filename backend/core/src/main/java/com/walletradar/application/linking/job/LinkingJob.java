package com.walletradar.application.linking.job;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.LinkingCompletedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainReclassificationCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.application.linking.config.LinkingProperties;
import com.walletradar.application.pipeline.job.support.StageExecutionLogSupport;
import com.walletradar.session.application.SessionPipelineActivityService;
import com.walletradar.session.application.SessionPipelineStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated pipeline stage that applies deterministic linking after all source-local
 * classification work for the active session window is ready.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinkingJob {

    private static final String STAGE_NAME = "linking";
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final LinkingProperties properties;
    private final LinkingBatchProcessor linkingBatchProcessor;
    private final LinkingDataGateService linkingDataGateService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SessionPipelineActivityService sessionPipelineActivityService;
    private final SessionPipelineStateService sessionPipelineStateService;

    public int runLinking() {
        return runLinking("manual", null, false);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onOnChainReclassificationCompleted(OnChainReclassificationCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runLinking("on-chain-reclassification-completed", event.sessionId(), true);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onBybitNormalizationCompleted(BybitNormalizationCompletedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runLinking("bybit-normalization-completed", event.sessionId(), true);
    }

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onLinkingRequested(LinkingRequestedEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        runLinking(event.trigger(), event.sessionId(), true);
    }

    private int runLinking(String trigger, String sessionId, boolean publishWhenEmpty) {
        if (!running.compareAndSet(false, true)) {
            log.debug("LinkingJob skipped: already running, trigger={}", trigger);
            return 0;
        }

        int processed = 0;
        AtomicReference<Instant> lastHeartbeatAt = new AtomicReference<>(Instant.now());
        long startedAtNanos = StageExecutionLogSupport.logStart(log, STAGE_NAME, trigger);
        try {
            if (sessionId != null && !sessionId.isBlank()) {
                LinkingDataGateService.LinkingGateSnapshot gateSnapshot = linkingDataGateService.snapshot(sessionId);
                if (!gateSnapshot.ready()) {
                    log.debug(
                            "LinkingJob gate blocked: sessionId={}, pendingOnChainClassification={}, pendingClarification={}, pendingReclassification={}, pendingBybitClassification={}, classificationStillRunning={}, trigger={}",
                            sessionId,
                            gateSnapshot.pendingOnChainClassificationCount(),
                            gateSnapshot.pendingClarificationCount(),
                            gateSnapshot.pendingReclassificationCount(),
                            gateSnapshot.pendingBybitClassificationCount(),
                            gateSnapshot.classificationStillRunning(),
                            trigger
                    );
                    return 0;
                }
            }
            sessionPipelineActivityService.markRunning(sessionId, UserSession.PipelineStage.LINKING);
            sessionPipelineStateService.markStageRunning(
                    sessionId,
                    UserSession.PipelineStage.LINKING,
                    "Linking running"
            );

            // ── Split convergence loop ────────────────────────────────────────────
            // 1. Inner loop: run only convergent (batchSize-aware) passes until they
            //    return 0 — no more candidates left for this cycle.
            // 2. Outer iteration: run terminal (full-scan) passes exactly once after
            //    convergence.  If terminal passes produce work, loop back to step 1
            //    so convergent passes can process anything that was unlocked.
            while (true) {
                // Step 1 — convergent passes
                while (true) {
                    int n = linkingBatchProcessor.processConvergentPasses(
                            properties.getBatchSize(),
                            () -> lastHeartbeatAt.set(maybeHeartbeat(sessionId, lastHeartbeatAt.get()))
                    );
                    processed += n;
                    lastHeartbeatAt.set(maybeHeartbeat(sessionId, lastHeartbeatAt.get()));
                    if (n == 0) {
                        break;
                    }
                }

                // Step 2 — terminal passes (full-scan, run once per convergence)
                int terminalProcessed = linkingBatchProcessor.runTerminalPasses(
                        properties.getBatchSize(),
                        () -> lastHeartbeatAt.set(maybeHeartbeat(sessionId, lastHeartbeatAt.get()))
                );
                processed += terminalProcessed;
                lastHeartbeatAt.set(maybeHeartbeat(sessionId, lastHeartbeatAt.get()));

                if (terminalProcessed == 0) {
                    // Both convergent and terminal passes are done — linking complete.
                    sessionPipelineStateService.markStageComplete(
                            sessionId,
                            UserSession.PipelineStage.LINKING,
                            "Linking complete"
                    );
                    publishCompletionEvent(sessionId, processed, trigger, publishWhenEmpty);
                    return processed;
                }
                // Terminal passes unlocked new work — loop back to convergent passes.
            }
        } catch (RuntimeException error) {
            sessionPipelineStateService.markStageFailed(
                    sessionId,
                    UserSession.PipelineStage.LINKING,
                    error.getMessage()
            );
            throw error;
        } finally {
            StageExecutionLogSupport.logFinish(log, STAGE_NAME, trigger, processed, startedAtNanos);
            sessionPipelineActivityService.markFinished(sessionId, UserSession.PipelineStage.LINKING);
            running.set(false);
        }
    }

    private Instant maybeHeartbeat(String sessionId, Instant lastHeartbeatAt) {
        Instant now = Instant.now();
        if (Duration.between(lastHeartbeatAt, now).compareTo(HEARTBEAT_INTERVAL) < 0) {
            return lastHeartbeatAt;
        }
        sessionPipelineActivityService.heartbeat(sessionId, UserSession.PipelineStage.LINKING);
        sessionPipelineStateService.markStageRunning(
                sessionId,
                UserSession.PipelineStage.LINKING,
                "Linking running"
        );
        return now;
    }

    private void publishCompletionEvent(String sessionId, int processed, String trigger, boolean publishWhenEmpty) {
        if (processed <= 0 && !publishWhenEmpty) {
            return;
        }
        applicationEventPublisher.publishEvent(new LinkingCompletedEvent(sessionId, processed, trigger));
    }
}
