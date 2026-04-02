package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPipelineResumeSchedulerTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void publishesResumeEventWhenBackfillIsCompleteAndPendingRawExists() {
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.ETHEREUM, NetworkId.BASE))
        );
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true),
                syncStatus("0xabc", NetworkId.BASE, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(true);

        SessionPipelineResumeScheduler scheduler = new SessionPipelineResumeScheduler(
                userSessionRepository,
                syncStatusRepository,
                mongoOperations,
                applicationEventPublisher
        );

        scheduler.resumeReadySessions();

        ArgumentCaptor<SessionBackfillCompletedEvent> captor = ArgumentCaptor.forClass(SessionBackfillCompletedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().targetCount()).isEqualTo(2);
    }

    @Test
    void doesNotPublishWhenPipelineIsAlreadyRunning() {
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.ETHEREUM))
        );
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));

        SessionPipelineResumeScheduler scheduler = new SessionPipelineResumeScheduler(
                userSessionRepository,
                syncStatusRepository,
                mongoOperations,
                applicationEventPublisher
        );

        scheduler.resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any(SessionBackfillCompletedEvent.class));
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
