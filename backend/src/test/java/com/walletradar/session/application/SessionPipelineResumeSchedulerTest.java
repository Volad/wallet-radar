package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.BybitNormalizationRequestedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainClarificationCompletedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.PricingRequestedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPipelineResumeSchedulerTest {

    @Mock
    private com.walletradar.domain.session.UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private com.walletradar.ingestion.job.linking.LinkingDataGateService linkingDataGateService;
    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;
    @Mock
    private SessionPipelineActivityService sessionPipelineActivityService;

    @Test
    void publishesBackfillResumeEventWhenPendingRawExists() {
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.ETHEREUM, NetworkId.BASE))
        );
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
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
    void integrationOnlySessionCanResumeFromBackfillCompletion() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setWallets(List.of());
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setAccountRef("BYBIT:33625378");
        integration.setStatus(UserSession.IntegrationStatus.READY);
        session.setIntegrations(List.of(integration));

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(backfillSegmentRepository.countByIntegrationId("BYBIT-33625378")).thenReturn(6L);
        when(backfillSegmentRepository.countByIntegrationIdAndStatus("BYBIT-33625378", com.walletradar.domain.sync.BackfillSegment.SegmentStatus.COMPLETE))
                .thenReturn(6L);
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(false);

        scheduler().resumeReadySessions();

        assertPublishedEvent(SessionBackfillCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.walletCount()).isZero();
            assertThat(event.targetCount()).isEqualTo(1);
        });
    }

    @Test
    void resumesClarificationWhenPendingClarificationExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
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
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false, false);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(true);

        scheduler().resumeReadySessions();

        assertPublishedEvent(BybitNormalizationRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
        verify(mongoOperations).exists(
                argThat(SessionPipelineResumeSchedulerTest::containsProcessableBybitRawCriteria),
                eq(ExternalLedgerRaw.class)
        );
    }

    @Test
    void resumesBybitWhenExtractedRowsExist() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false, false);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(true);

        scheduler().resumeReadySessions();

        assertPublishedEvent(BybitNormalizationRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
        verify(mongoOperations).exists(
                argThat(SessionPipelineResumeSchedulerTest::containsProcessableBybitRawCriteria),
                eq(BybitExtractedEvent.class)
        );
    }

    @Test
    void unmatchedBybitBridgeRowsResumeDedicatedLinkingInsteadOfBybitNormalization() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false);
        when(linkingDataGateService.hasPendingLinking("session-1")).thenReturn(true);

        scheduler().resumeReadySessions();

        assertPublishedEvent(LinkingRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void doesNotResumeLinkingAfterReplayAlreadyCompleted() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        state.setStatus(UserSession.PipelineStatus.COMPLETE);
        state.setUpdatedAt(Instant.now());
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false);
        when(linkingDataGateService.hasPendingLinking("session-1")).thenReturn(true);

        scheduler().resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void resumesPricingWhenPendingPriceExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, true, false, false);
        when(linkingDataGateService.hasPendingLinking("session-1")).thenReturn(false);

        scheduler().resumeReadySessions();

        assertPublishedEvent(PricingRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void resumesReplayWhenPendingStatExists() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
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
        when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any()))
                .thenReturn(java.util.Optional.of(new SessionPipelineActivityService.ActivitySnapshot(
                        UserSession.PipelineStage.ON_CHAIN_NORMALIZATION,
                        Instant.now()
                )));

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
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
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

    @Test
    void staleAccountingReplayRunningStateHealsToCompleteWhenReplayOutputsExist() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now().minusSeconds(3600));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq("asset_ledger_points"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq("on_chain_balances"))).thenReturn(true);

        scheduler().resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(sessionPipelineStateService).markStageComplete(
                "session-1",
                UserSession.PipelineStage.ACCOUNTING_REPLAY,
                "Accounting replay complete"
        );
    }

    @Test
    void replayBootstrapRemainsRequiredWhenOnlyUnrelatedLedgerRowsExist() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, true);
        when(mongoOperations.exists(argThat(query -> containsAccountingUniverseId(query, "session-1")), eq("asset_ledger_points")))
                .thenReturn(false);

        scheduler().resumeReadySessions();

        assertPublishedEvent(PricingCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void staleReplayRunningDoesNotHealFromUnrelatedDerivedRows() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now().minusSeconds(3600));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        when(mongoOperations.exists(any(Query.class), eq(RawTransaction.class))).thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("normalized_transactions"))).thenReturn(true);
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(false, false, false, false);
        when(mongoOperations.exists(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(false, false);
        when(mongoOperations.exists(any(Query.class), eq(ExternalLedgerRaw.class))).thenReturn(false, false);
        when(mongoOperations.exists(argThat(query -> containsAccountingUniverseId(query, "session-1")), eq("asset_ledger_points")))
                .thenReturn(false);

        scheduler().resumeReadySessions();

        verify(sessionPipelineStateService, never()).markStageComplete(
                eq("session-1"),
                eq(UserSession.PipelineStage.ACCOUNTING_REPLAY),
                any()
        );
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    private SessionPipelineResumeScheduler scheduler() {
        lenient().when(accountingUniverseService.resolveScope(any(UserSession.class))).thenAnswer(invocation -> {
            UserSession session = invocation.getArgument(0);
            List<String> onChainWalletRefs = session.getWallets() == null ? List.of() : session.getWallets().stream()
                    .map(UserSession.SessionWallet::getAddress)
                    .toList();
            List<String> memberRefs = new java.util.ArrayList<>(onChainWalletRefs);
            if (session.getIntegrations() != null) {
                session.getIntegrations().stream()
                        .map(UserSession.SessionIntegration::getAccountRef)
                        .filter(java.util.Objects::nonNull)
                        .forEach(memberRefs::add);
            }
            return new AccountingUniverseService.AccountingUniverseScope(
                    session.getId(),
                    List.copyOf(memberRefs),
                    List.copyOf(onChainWalletRefs)
            );
        });
        return new SessionPipelineResumeScheduler(
                userSessionRepository,
                syncStatusRepository,
                backfillSegmentRepository,
                accountingUniverseService,
                linkingDataGateService,
                mongoOperations,
                applicationEventPublisher,
                sessionPipelineStateService,
                sessionPipelineActivityService
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

    private static boolean containsWalletAddress(Query query, String walletAddress) {
        if (query == null || query.getQueryObject() == null) {
            return false;
        }
        return query.getQueryObject().toJson().contains(walletAddress);
    }

    private static boolean containsProcessableBybitRawCriteria(Query query) {
        if (query == null || query.getQueryObject() == null) {
            return false;
        }
        String json = query.getQueryObject().toJson();
        return json.contains("\"status\": \"RAW\"")
                && json.contains("\"basisRelevant\": true")
                && json.contains("\"outOfScope\"")
                && json.contains("\"canonicalType\"");
    }

    private static boolean containsAccountingUniverseId(Query query, String accountingUniverseId) {
        if (query == null || query.getQueryObject() == null) {
            return false;
        }
        return query.getQueryObject().toJson().contains("\"accountingUniverseId\"") &&
                query.getQueryObject().toJson().contains(accountingUniverseId);
    }
}
