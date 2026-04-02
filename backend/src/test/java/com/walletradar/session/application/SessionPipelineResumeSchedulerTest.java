package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.BybitNormalizationCompletedEvent;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPipelineResumeSchedulerTest {

    @Mock
    private com.walletradar.domain.session.UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void publishesBackfillResumeEventWhenPendingRawExists() {
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

        scheduler().resumeReadySessions();

        assertPublishedEvent(SessionBackfillCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.targetCount()).isEqualTo(2);
        });
    }

    @Test
    void resumesClarificationWhenPendingClarificationExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(true, false, false, false, false);

        scheduler().resumeReadySessions();

        assertPublishedEvent(OnChainNormalizationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void resumesBybitWhenRawLedgerRowsExist() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(true);

        scheduler().resumeReadySessions();

        assertPublishedEvent(OnChainClarificationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void resumesPricingWhenPendingPriceExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, true, false, false);

        scheduler().resumeReadySessions();

        assertPublishedEvent(BybitNormalizationCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void resumesReplayWhenPendingStatExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, true);

        scheduler().resumeReadySessions();

        assertPublishedEvent(PricingCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void doesNotPublishWhenPipelineIsFreshlyRunning() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ON_CHAIN_NORMALIZATION);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now());
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));

        scheduler().resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void staleRunningStateDoesNotBlockResume() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ON_CHAIN_CLARIFICATION);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now().minusSeconds(3600));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(true, false, false, false, false);

        scheduler().resumeReadySessions();

        verify(applicationEventPublisher).publishEvent(any(OnChainNormalizationCompletedEvent.class));
    }

    private SessionPipelineResumeScheduler scheduler() {
        return new SessionPipelineResumeScheduler(
                userSessionRepository,
                syncStatusRepository,
                mongoOperations,
                applicationEventPublisher
        );
    }

    private <T> void assertPublishedEvent(Class<T> eventType, java.util.function.Consumer<T> assertion) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(eventType, assertion);
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
