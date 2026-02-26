package com.walletradar.ingestion.job.sync;

import com.walletradar.ingestion.sync.balance.BalanceRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CurrentBalancePollJobTest {

    @Mock
    private BalanceRefreshService balanceRefreshService;

    @InjectMocks
    private CurrentBalancePollJob job;

    @Test
    @DisplayName("scheduled job delegates to refresh all known wallet networks")
    void runScheduled_delegatesToService() {
        job.runScheduled();
        verify(balanceRefreshService).refreshAllKnownWalletNetworks();
    }
}

