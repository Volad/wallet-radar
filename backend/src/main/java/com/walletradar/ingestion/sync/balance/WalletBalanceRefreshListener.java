package com.walletradar.ingestion.sync.balance;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.BalanceRefreshRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Asynchronous listener for immediate balance refresh requests (e.g. on wallet add).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletBalanceRefreshListener {

    private final BalanceRefreshService balanceRefreshService;

    @Async(AsyncConfig.SYNC_EXECUTOR)
    @EventListener
    public void onBalanceRefreshRequested(BalanceRefreshRequestedEvent event) {
        if (event.walletAddress() == null || event.walletAddress().isBlank()) {
            return;
        }
        balanceRefreshService.refreshWallets(List.of(event.walletAddress()), event.networks());
    }
}

