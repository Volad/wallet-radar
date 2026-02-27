package com.walletradar.ingestion.sync.balance;

import com.walletradar.domain.BalanceRefreshRequestedEvent;
import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WalletBalanceRefreshListenerTest {

    @Mock
    private BalanceRefreshService balanceRefreshService;

    @InjectMocks
    private WalletBalanceRefreshListener listener;

    @Test
    @DisplayName("listener delegates to balance refresh service for wallet and networks")
    void onBalanceRefreshRequested_delegatesToService() {
        BalanceRefreshRequestedEvent event = new BalanceRefreshRequestedEvent(
                "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
                List.of(NetworkId.ARBITRUM, NetworkId.ETHEREUM)
        );

        listener.onBalanceRefreshRequested(event);

        verify(balanceRefreshService).refreshWallets(
                List.of("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f"),
                List.of(NetworkId.ARBITRUM, NetworkId.ETHEREUM));
    }
}

