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
        balanceRefreshService.refreshAllKnownWalletNetworks();
    }
}

