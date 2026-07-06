package com.walletradar.lending.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.walletradar.config.SchedulerConfig.SCHEDULER_POOL;

@Slf4j
@Component
@RequiredArgsConstructor
public class LendingHealthFactorRefreshJob {

    private final LendingHealthFactorRefreshService refreshService;
    private final @Qualifier(SCHEDULER_POOL) TaskScheduler taskScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
        taskScheduler.schedule(this::refresh, Instant.now().plusSeconds(300));
    }

    @Scheduled(fixedDelayString = "${walletradar.lending.health-factor.refresh-interval-ms:600000}")
    public void scheduledRefresh() {
        refresh();
    }

    private void refresh() {
        if (!running.compareAndSet(false, true)) {
            log.info("Lending health factor refresh skipped because a previous run is still active");
            return;
        }
        try {
            refreshService.refreshActiveBorrowGroups();
        } finally {
            running.set(false);
        }
    }
}
