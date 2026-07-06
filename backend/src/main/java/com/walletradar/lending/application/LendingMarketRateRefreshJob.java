package com.walletradar.lending.application;

import com.walletradar.lending.config.LendingMarketRateProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class LendingMarketRateRefreshJob {

    private final LendingMarketRateProperties properties;
    private final LendingMarketRateRefreshService refreshService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isStartupRefreshEnabled()) {
            refresh();
        }
    }

    @Scheduled(fixedDelayString = "${walletradar.lending.market-rates.refresh-interval-ms:86400000}")
    public void scheduledRefresh() {
        refresh();
    }

    private void refresh() {
        if (!running.compareAndSet(false, true)) {
            log.info("Lending market rate refresh skipped because a previous run is still active");
            return;
        }
        try {
            refreshService.refreshActiveMarkets();
        } finally {
            running.set(false);
        }
    }
}
