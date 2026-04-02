package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.event.WalletNetworkBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.session.application.SessionPipelineStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionBackfillCompletionPublisherTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;

    @Test
    void publishesSessionCompletionWhenAllTargetsAreComplete() {
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.ETHEREUM, NetworkId.BASE)),
                wallet("0xdef", List.of(NetworkId.ARBITRUM))
        );

        when(userSessionRepository.findAllByWalletsAddress("0xabc")).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc", "0xdef"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true),
                syncStatus("0xabc", NetworkId.BASE, true),
                syncStatus("0xdef", NetworkId.ARBITRUM, true)
        ));

        SessionBackfillCompletionPublisher publisher = new SessionBackfillCompletionPublisher(
                userSessionRepository,
                syncStatusRepository,
                applicationEventPublisher,
                sessionPipelineStateService
        );

        publisher.onWalletNetworkBackfillCompleted(new WalletNetworkBackfillCompletedEvent("0xabc", NetworkId.BASE));

        ArgumentCaptor<SessionBackfillCompletedEvent> captor = ArgumentCaptor.forClass(SessionBackfillCompletedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().walletCount()).isEqualTo(2);
        assertThat(captor.getValue().targetCount()).isEqualTo(3);
    }

    @Test
    void doesNotPublishSessionCompletionWhenAnyTargetIsStillIncomplete() {
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.ETHEREUM, NetworkId.BASE))
        );

        when(userSessionRepository.findAllByWalletsAddress("0xabc")).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true),
                syncStatus("0xabc", NetworkId.BASE, false)
        ));

        SessionBackfillCompletionPublisher publisher = new SessionBackfillCompletionPublisher(
                userSessionRepository,
                syncStatusRepository,
                applicationEventPublisher,
                sessionPipelineStateService
        );

        publisher.onWalletNetworkBackfillCompleted(new WalletNetworkBackfillCompletedEvent("0xabc", NetworkId.ETHEREUM));

        verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(SessionBackfillCompletedEvent.class));
    }

    private static UserSession session(String id, UserSession.SessionWallet... wallets) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setWallets(List.of(wallets));
        return session;
    }

    private static UserSession.SessionWallet wallet(String address, List<NetworkId> networks) {
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress(address);
        wallet.setNetworks(networks);
        return wallet;
    }

    private static SyncStatus syncStatus(String walletAddress, NetworkId networkId, boolean complete) {
        SyncStatus status = new SyncStatus();
        status.setWalletAddress(walletAddress);
        status.setNetworkId(networkId.name());
        status.setBackfillComplete(complete);
        return status;
    }
}
