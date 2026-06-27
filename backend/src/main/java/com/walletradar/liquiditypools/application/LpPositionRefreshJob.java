package com.walletradar.liquiditypools.application;

import com.walletradar.config.AsyncConfig;
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

    private final LpPositionRefreshService refreshService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener
    @Async(AsyncConfig.PIPELINE_STAGE_EXECUTOR)
    public void onAccountingReplayCompleted(AccountingReplayCompletedEvent event) {
        if (event == null) {
            return;
        }
        log.info("LP position refresh triggered by accounting-replay-completed sessionId={}",
                event.sessionId());
        refresh();
    }

    @Scheduled(fixedDelayString = "${walletradar.liquidity-pools.refresh-interval-ms:3600000}")
    public void scheduledRefresh() {
        refresh();
    }

    private void refresh() {
        if (!running.compareAndSet(false, true)) {
            log.info("LP position refresh skipped because a previous run is still active");
            return;
        }
        try {
            refreshService.refreshAllOpenPositions();
        } finally {
            running.set(false);
        }
    }
}
