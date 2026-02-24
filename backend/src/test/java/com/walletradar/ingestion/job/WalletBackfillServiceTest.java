package com.walletradar.ingestion.job;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.domain.WalletAddedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletBackfillServiceTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private WalletBackfillService walletBackfillService;

    @Test
    @DisplayName("addWallet upserts PENDING and publishes WalletAddedEvent")
    void addWallet_upsertsAndPublishes() {
        when(syncStatusRepository.findByWalletAddressAndNetworkId(any(), any())).thenReturn(java.util.Optional.empty());

        walletBackfillService.addWallet("0x742d35Cc6634C0532925a3b844Bc454e4438f44e", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM));

        ArgumentCaptor<SyncStatus> statusCaptor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository, times(2)).save(statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).hasSize(2);
        assertThat(statusCaptor.getAllValues().get(0).getStatus()).isEqualTo(SyncStatus.SyncStatusValue.PENDING);
        assertThat(statusCaptor.getAllValues().get(0).getWalletAddress()).isEqualTo("0x742d35Cc6634C0532925a3b844Bc454e4438f44e");

        ArgumentCaptor<WalletAddedEvent> eventCaptor = ArgumentCaptor.forClass(WalletAddedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().walletAddress()).isEqualTo("0x742d35Cc6634C0532925a3b844Bc454e4438f44e");
        assertThat(eventCaptor.getValue().networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("addWallet with empty networks uses all networks")
    void addWallet_emptyNetworks_usesAllNetworks() {
        when(syncStatusRepository.findByWalletAddressAndNetworkId(any(), any())).thenReturn(java.util.Optional.empty());

        walletBackfillService.addWallet("0x742d35Cc6634C0532925a3b844Bc454e4438f44e", Collections.emptyList());

        verify(syncStatusRepository, times(NetworkId.values().length)).save(any(SyncStatus.class));
        ArgumentCaptor<WalletAddedEvent> eventCaptor = ArgumentCaptor.forClass(WalletAddedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().networks()).containsExactly(NetworkId.values());
    }
}
