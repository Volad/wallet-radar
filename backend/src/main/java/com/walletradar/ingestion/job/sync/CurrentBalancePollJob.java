package com.walletradar.ingestion.job.sync;

import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic on-chain balance poll (T-013). Refreshes all known wallet×network pairs from sync_status.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurrentBalancePollJob {

    private final BalanceRefreshService balanceRefreshService;

    @Scheduled(
            fixedRateString = "${walletradar.ingestion.balance.poll-interval-ms:600000}",
            initialDelayString = "${walletradar.ingestion.balance.poll-interval-ms:600000}")
    public void runScheduled() {
        long startedAt = System.currentTimeMillis();
        log.info("CurrentBalancePollJob started");
        try {
            balanceRefreshService.refreshAllKnownWalletNetworks();
            log.info("CurrentBalancePollJob finished: durationMs={}", System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("CurrentBalancePollJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }
}
