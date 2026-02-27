package com.walletradar.ingestion.wallet.command;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.domain.WalletAddedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(2)).publishEvent(eventsCaptor.capture());
        List<Object> events = eventsCaptor.getAllValues();
        assertThat(events.stream().filter(WalletAddedEvent.class::isInstance).count()).isEqualTo(1);
        assertThat(events.stream().filter(com.walletradar.domain.BalanceRefreshRequestedEvent.class::isInstance).count()).isEqualTo(1);
        WalletAddedEvent walletAddedEvent = (WalletAddedEvent) events.stream()
                .filter(WalletAddedEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
        com.walletradar.domain.BalanceRefreshRequestedEvent balanceEvent =
                (com.walletradar.domain.BalanceRefreshRequestedEvent) events.stream()
                        .filter(com.walletradar.domain.BalanceRefreshRequestedEvent.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertThat(walletAddedEvent.walletAddress()).isEqualTo("0x742d35Cc6634C0532925a3b844Bc454e4438f44e");
        assertThat(walletAddedEvent.networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
        assertThat(balanceEvent.walletAddress()).isEqualTo("0x742d35Cc6634C0532925a3b844Bc454e4438f44e");
        assertThat(balanceEvent.networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("addWallet skips already-complete networks and only backfills pending ones")
    void addWallet_skipsCompleteNetworks() {
        SyncStatus completeStatus = new SyncStatus();
        completeStatus.setId("existing-id");
        completeStatus.setWalletAddress("0xABC");
        completeStatus.setNetworkId(NetworkId.ETHEREUM.name());
        completeStatus.setBackfillComplete(true);

        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", NetworkId.ETHEREUM.name()))
                .thenReturn(Optional.of(completeStatus));
        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", NetworkId.ARBITRUM.name()))
                .thenReturn(Optional.empty());

        walletBackfillService.addWallet("0xABC", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM));

        verify(syncStatusRepository, times(1)).save(any(SyncStatus.class));
        ArgumentCaptor<SyncStatus> statusCaptor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getNetworkId()).isEqualTo(NetworkId.ARBITRUM.name());

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(2)).publishEvent(eventsCaptor.capture());
        List<Object> events = eventsCaptor.getAllValues();
        WalletAddedEvent walletAddedEvent = (WalletAddedEvent) events.stream()
                .filter(WalletAddedEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
        com.walletradar.domain.BalanceRefreshRequestedEvent balanceEvent =
                (com.walletradar.domain.BalanceRefreshRequestedEvent) events.stream()
                        .filter(com.walletradar.domain.BalanceRefreshRequestedEvent.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertThat(walletAddedEvent.networks()).containsExactly(NetworkId.ARBITRUM);
        assertThat(balanceEvent.networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("addWallet publishes balance refresh even when all networks are already complete")
    void addWallet_allComplete_publishesBalanceRefreshOnly() {
        SyncStatus completeEth = new SyncStatus();
        completeEth.setId("id-eth");
        completeEth.setBackfillComplete(true);
        SyncStatus completeArb = new SyncStatus();
        completeArb.setId("id-arb");
        completeArb.setBackfillComplete(true);

        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", NetworkId.ETHEREUM.name()))
                .thenReturn(Optional.of(completeEth));
        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", NetworkId.ARBITRUM.name()))
                .thenReturn(Optional.of(completeArb));

        walletBackfillService.addWallet("0xABC", List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM));

        verify(syncStatusRepository, never()).save(any());
        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(1)).publishEvent(eventsCaptor.capture());
        List<Object> events = eventsCaptor.getAllValues();
        assertThat(events.stream().filter(WalletAddedEvent.class::isInstance)).isEmpty();
        com.walletradar.domain.BalanceRefreshRequestedEvent balanceEvent =
                (com.walletradar.domain.BalanceRefreshRequestedEvent) events.get(0);
        assertThat(balanceEvent.networks()).containsExactly(NetworkId.ETHEREUM, NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("addWallet resets retryCount and nextRetryAfter for non-complete FAILED status")
    void addWallet_resetsRetryStateForFailedStatus() {
        SyncStatus failedStatus = new SyncStatus();
        failedStatus.setId("existing-id");
        failedStatus.setWalletAddress("0xABC");
        failedStatus.setNetworkId(NetworkId.ETHEREUM.name());
        failedStatus.setStatus(SyncStatus.SyncStatusValue.FAILED);
        failedStatus.setRetryCount(3);
        failedStatus.setBackfillComplete(false);

        when(syncStatusRepository.findByWalletAddressAndNetworkId("0xABC", NetworkId.ETHEREUM.name()))
                .thenReturn(Optional.of(failedStatus));

        walletBackfillService.addWallet("0xABC", List.of(NetworkId.ETHEREUM));

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getRetryCount()).isEqualTo(0);
        assertThat(saved.getNextRetryAfter()).isNull();
        assertThat(saved.getStatus()).isEqualTo(SyncStatus.SyncStatusValue.PENDING);
    }

    @Test
    @DisplayName("addWallet with empty networks uses all networks")
    void addWallet_emptyNetworks_usesAllNetworks() {
        when(syncStatusRepository.findByWalletAddressAndNetworkId(any(), any())).thenReturn(java.util.Optional.empty());

        walletBackfillService.addWallet("0x742d35Cc6634C0532925a3b844Bc454e4438f44e", Collections.emptyList());

        verify(syncStatusRepository, times(NetworkId.values().length)).save(any(SyncStatus.class));
        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(2)).publishEvent(eventsCaptor.capture());
        List<Object> events = eventsCaptor.getAllValues();
        WalletAddedEvent walletAddedEvent = (WalletAddedEvent) events.stream()
                .filter(WalletAddedEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
        com.walletradar.domain.BalanceRefreshRequestedEvent balanceEvent =
                (com.walletradar.domain.BalanceRefreshRequestedEvent) events.stream()
                        .filter(com.walletradar.domain.BalanceRefreshRequestedEvent.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertThat(walletAddedEvent.networks()).containsExactly(NetworkId.values());
        assertThat(balanceEvent.networks()).containsExactly(NetworkId.values());
    }
}
