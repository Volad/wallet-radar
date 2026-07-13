package com.walletradar.application.pricing.latest;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled job that drives the independent latest-price refresh cycle.
 *
 * <p>Fires at application start (via {@link ApplicationReadyEvent}) and then on a fixed delay
 * controlled by {@code walletradar.pricing.latest.refresh-interval-ms} (default 30 min).
 *
 * <p>An {@link AtomicBoolean} guard prevents overlapping runs.
 */
@Component
@RequiredArgsConstructor
public class LatestPriceRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(LatestPriceRefreshJob.class);

    private final LatestPriceRefreshService latestPriceRefreshService;
    private final TrackedAssetRegistryMaintainer trackedAssetRegistryMaintainer;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("LatestPriceRefreshJob: application ready — running initial cycle");
        runCycle("startup");
    }

    @Scheduled(fixedDelayString = "${walletradar.pricing.latest.refresh-interval-ms:1800000}")
    public void scheduledRefresh() {
        runCycle("scheduled");
    }

    private void runCycle(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("LatestPriceRefreshJob: skipping cycle (already running), trigger={}", trigger);
            return;
        }
        try {
            log.info("LatestPriceRefreshJob: starting cycle, trigger={}", trigger);
            // Rebuild registry first so fresh symbols are included this cycle
            int tracked = trackedAssetRegistryMaintainer.rebuild();
            log.info("LatestPriceRefreshJob: registry rebuilt, tracked={}", tracked);

            LatestPriceRefreshResult result = latestPriceRefreshService.refresh();
            log.info(
                    "LatestPriceRefreshJob: cycle done, trigger={}, tracked={}, bybit={}, dzengi={}, byNone={}, divergences={}",
                    trigger,
                    result.tracked(),
                    result.resolvedBybit(),
                    result.resolvedDzengi(),
                    result.pricedByNeither(),
                    result.divergences()
            );
        } catch (Exception ex) {
            log.error("LatestPriceRefreshJob: cycle failed, trigger={}", trigger, ex);
        } finally {
            running.set(false);
        }
    }
}
