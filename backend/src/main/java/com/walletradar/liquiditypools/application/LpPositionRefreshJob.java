package com.walletradar.liquiditypools.application;

import com.walletradar.common.refresh.RefreshTrigger;
import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class LpPositionRefreshJob {

    private final LpRefreshOrchestrator refreshOrchestrator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener
    @Async(com.walletradar.config.AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onAccountingReplayCompleted(AccountingReplayCompletedEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()) {
            return;
        }
        String sessionId = event.sessionId().trim();
        if (refreshOrchestrator.shouldSkipSessionRefresh(sessionId, "accounting-replay-completed")) {
            log.info(
                    "LP position refresh skipped (TTL): sessionId={}, trigger=accounting-replay-completed",
                    sessionId
            );
            return;
        }
        log.info("LP position refresh triggered by accounting-replay-completed sessionId={}", sessionId);
        refreshSession(sessionId, RefreshTrigger.REPLAY);
    }

    @Scheduled(
            fixedDelayString = "${walletradar.liquidity-pools.refresh-interval-ms:3600000}",
            initialDelayString = "${walletradar.liquidity-pools.refresh-interval-ms:3600000}"
    )
    public void scheduledRefresh() {
        refreshAll(RefreshTrigger.SCHEDULED);
    }

    private void refreshAll(RefreshTrigger trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("LP position refresh skipped because a previous run is still active trigger={}", trigger);
            return;
        }
        try {
            refreshOrchestrator.triggerRefreshAllOpenPositions(trigger);
        } finally {
            running.set(false);
        }
    }

    private void refreshSession(String sessionId, RefreshTrigger trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("LP position refresh skipped because a previous run is still active sessionId={} trigger={}",
                    sessionId, trigger);
            return;
        }
        try {
            refreshOrchestrator.triggerRefreshAllOpenForSession(sessionId, trigger);
        } finally {
            running.set(false);
        }
    }
}
