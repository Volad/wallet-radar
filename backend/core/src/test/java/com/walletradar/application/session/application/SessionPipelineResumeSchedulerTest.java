package com.walletradar.application.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.event.AccountingReplayCompletedEvent;
import com.walletradar.domain.event.BybitNormalizationRequestedEvent;
import com.walletradar.domain.event.LinkingRequestedEvent;
import com.walletradar.domain.event.OnChainNormalizationCompletedEvent;
import com.walletradar.domain.event.OnChainReclassificationRequestedEvent;
import com.walletradar.domain.event.PricingCompletedEvent;
import com.walletradar.domain.event.PricingRequestedEvent;
import com.walletradar.domain.event.SessionBackfillCompletedEvent;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionPipelineResumeSchedulerTest {

    private final GateSnapshotStub gateStub = new GateSnapshotStub();

    @BeforeEach
    void resetGateStub() {
        gateStub.reset();
    }

    @Mock
    private com.walletradar.domain.session.UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private com.walletradar.application.linking.job.LinkingDataGateService linkingDataGateService;
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
        gateStub.pendingRawWallets.add("0xabc");

        scheduler().resumeReadySessions();

        assertPublishedEvent(SessionBackfillCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.targetCount()).isEqualTo(2);
        });
    }

    @Test
    void resumesStrandedBackfillWhenSyncStatusIsTerminalButCompletionFlagIsStale() {
        // LINEA stall: session stuck in BACKFILL/RUNNING, a sync_status reached terminal COMPLETE but
        // its backfillComplete boolean was left false. The watchdog gate must treat terminal status as
        // authoritative and advance the pipeline instead of returning null forever.
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.BASE, NetworkId.LINEA))
        );
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.BACKFILL);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now().minusSeconds(3600));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.BASE, true, SyncStatus.SyncStatusValue.COMPLETE),
                syncStatus("0xabc", NetworkId.LINEA, false, SyncStatus.SyncStatusValue.COMPLETE)
        ));
        gateStub.pendingRawWallets.add("0xabc");

        scheduler().resumeReadySessions();

        assertPublishedEvent(SessionBackfillCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.targetCount()).isEqualTo(2);
        });
    }

    @Test
    void doesNotResumeWhenSyncStatusIsGenuinelyRunningWithIncompleteFlag() {
        // Guard: a source that is genuinely still fetching (status=RUNNING, backfillComplete=false) must
        // NOT be treated as complete by the terminal-status robustness net.
        UserSession session = session(
                "session-1",
                wallet("0xabc", List.of(NetworkId.BASE, NetworkId.LINEA))
        );
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.BACKFILL);
        state.setStatus(UserSession.PipelineStatus.RUNNING);
        state.setUpdatedAt(Instant.now().minusSeconds(3600));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.BASE, true, SyncStatus.SyncStatusValue.COMPLETE),
                syncStatus("0xabc", NetworkId.LINEA, false, SyncStatus.SyncStatusValue.RUNNING)
        ));

        scheduler().resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any());
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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.pendingClarificationWallets.add("0xabc");

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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.bybitRawSessions.add("session-1");

        scheduler().resumeReadySessions();

        assertPublishedEvent(BybitNormalizationRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
        verify(mongoOperations).findDistinct(
                argThat(SessionPipelineResumeSchedulerTest::containsProcessableBybitRawCriteria),
                eq("sessionId"),
                eq(ExternalLedgerRaw.class),
                eq(String.class)
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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.bybitExtractedSessions.add("session-1");

        scheduler().resumeReadySessions();

        assertPublishedEvent(BybitNormalizationRequestedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
        verify(mongoOperations).findDistinct(
                argThat(SessionPipelineResumeSchedulerTest::containsProcessableBybitRawCriteria),
                eq("sessionId"),
                eq(BybitExtractedEvent.class),
                eq(String.class)
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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.balanceSessions.add("session-1");
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
        gateStub.normalizedWallets.add("0xabc");
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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.pendingPriceWallets.add("0xabc");
        gateStub.balanceSessions.add("session-1");
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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.pendingStatWallets.add("0xabc");
        gateStub.balanceSessions.add("session-1");

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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.pendingClarificationWallets.add("0xabc");

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
        gateStub.normalizedWallets.add("0xabc");
        gateStub.balanceSessions.add("session-1");
        when(mongoOperations.exists(any(Query.class), eq("asset_ledger_points"))).thenReturn(true);
        scheduler().resumeReadySessions();

        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(sessionPipelineStateService).markStageComplete(
                "session-1",
                UserSession.PipelineStage.ACCOUNTING_REPLAY,
                "Accounting replay complete"
        );
    }

    @Test
    void resumesPortfolioSnapshotWhenReplayOutputsExistButSnapshotIsNotComplete() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        UserSession.PipelineState state = new UserSession.PipelineState();
        state.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        state.setStatus(UserSession.PipelineStatus.COMPLETE);
        state.setUpdatedAt(Instant.now().minusSeconds(60));
        session.setPipelineState(state);

        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        gateStub.normalizedWallets.add("0xabc");
        gateStub.ledgerUniverses.add("session-1");
        gateStub.balanceSessions.add("session-1");

        scheduler().resumeReadySessions();

        assertPublishedEvent(AccountingReplayCompletedEvent.class, event -> {
            assertThat(event.sessionId()).isEqualTo("session-1");
            assertThat(event.trigger()).isEqualTo("resume-watchdog");
        });
    }

    @Test
    void replayBootstrapRemainsRequiredWhenOnlyUnrelatedLedgerRowsExist() {
        UserSession session = session("session-1", wallet("0xabc", List.of(NetworkId.ETHEREUM)));
        when(userSessionRepository.findAll()).thenReturn(List.of(session));
        lenient().when(sessionPipelineActivityService.latestFreshActivity(eq("session-1"), any())).thenReturn(java.util.Optional.empty());
        when(syncStatusRepository.findByWalletAddressIn(List.of("0xabc"))).thenReturn(List.of(
                syncStatus("0xabc", NetworkId.ETHEREUM, true)
        ));
        gateStub.normalizedWallets.add("0xabc");
        gateStub.confirmedWallets.add("0xabc");
        gateStub.balanceSessions.add("session-1");

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
        gateStub.normalizedWallets.add("0xabc");
        when(mongoOperations.exists(argThat(query -> containsAccountingUniverseId(query, "session-1")), eq("asset_ledger_points")))
                .thenReturn(false);
        when(mongoOperations.exists(any(Query.class), eq("on_chain_balances"))).thenReturn(true);

        scheduler().resumeReadySessions();

        verify(sessionPipelineStateService, never()).markStageComplete(
                eq("session-1"),
                eq(UserSession.PipelineStage.ACCOUNTING_REPLAY),
                any()
        );
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    private SessionPipelineResumeScheduler scheduler() {
        wireGateSnapshotFindDistinct();
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

    private static SyncStatus syncStatus(
            String walletAddress,
            NetworkId networkId,
            boolean complete,
            SyncStatus.SyncStatusValue statusValue
    ) {
        SyncStatus status = syncStatus(walletAddress, networkId, complete);
        status.setStatus(statusValue);
        return status;
    }

    private void wireGateSnapshotFindDistinct() {
        lenient().when(mongoOperations.findDistinct(any(Query.class), anyString(), any(Class.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    String field = invocation.getArgument(1);
                    Query query = invocation.getArgument(0);
                    Object entity = invocation.getArgument(2);
                    if ("sessionId".equals(field)) {
                        return gateStub.resolveSessionIds(query, entity);
                    }
                    if ("accountingUniverseId".equals(field)) {
                        return List.copyOf(gateStub.ledgerUniverses);
                    }
                    return gateStub.resolveWalletAddresses(query, entity);
                });
        lenient().when(mongoOperations.findDistinct(any(Query.class), anyString(), anyString(), eq(String.class)))
                .thenAnswer(invocation -> gateStub.resolve(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));
    }

    private static final class GateSnapshotStub {
        private final Set<String> pendingRawWallets = new HashSet<>();
        private final Set<String> normalizedWallets = new HashSet<>();
        private final Set<String> pendingClarificationWallets = new HashSet<>();
        private final Set<String> pendingReclassificationWallets = new HashSet<>();
        private final Set<String> pendingPriceWallets = new HashSet<>();
        private final Set<String> pendingStatWallets = new HashSet<>();
        private final Set<String> confirmedWallets = new HashSet<>();
        private final Set<String> ledgerWallets = new HashSet<>();
        private final Set<String> balanceSessions = new HashSet<>();
        private final Set<String> bybitExtractedSessions = new HashSet<>();
        private final Set<String> bybitRawSessions = new HashSet<>();
        private final Set<String> dzengiExtractedSessions = new HashSet<>();
        private final Set<String> ledgerUniverses = new HashSet<>();

        void reset() {
            pendingRawWallets.clear();
            normalizedWallets.clear();
            pendingClarificationWallets.clear();
            pendingReclassificationWallets.clear();
            pendingPriceWallets.clear();
            pendingStatWallets.clear();
            confirmedWallets.clear();
            ledgerWallets.clear();
            balanceSessions.clear();
            bybitExtractedSessions.clear();
            bybitRawSessions.clear();
            dzengiExtractedSessions.clear();
            ledgerUniverses.clear();
        }

        List<String> resolveWalletAddresses(Query query, Object entityOrCollection) {
            String json = queryJson(query);
            if (entityOrCollection == RawTransaction.class && json.contains("normalizationStatus")) {
                return List.copyOf(pendingRawWallets);
            }
            if (entityOrCollection == NormalizedTransaction.class) {
                if (json.contains("PENDING_CLARIFICATION")) {
                    return List.copyOf(pendingClarificationWallets);
                }
                if (json.contains("PENDING_RECLASSIFICATION")) {
                    return List.copyOf(pendingReclassificationWallets);
                }
                if (json.contains("PENDING_PRICE")) {
                    return List.copyOf(pendingPriceWallets);
                }
                if (json.contains("PENDING_STAT")) {
                    return List.copyOf(pendingStatWallets);
                }
                if (json.contains("CONFIRMED")) {
                    return List.copyOf(confirmedWallets);
                }
            }
            if ("asset_ledger_points".equals(entityOrCollection)) {
                return List.copyOf(ledgerWallets);
            }
            if ("normalized_transactions".equals(entityOrCollection) && !json.contains("\"status\"")) {
                return List.copyOf(normalizedWallets);
            }
            return List.of();
        }

        List<String> resolveSessionIds(Query query, Object entityOrCollection) {
            if (entityOrCollection == BybitExtractedEvent.class) {
                return List.copyOf(bybitExtractedSessions);
            }
            if (entityOrCollection == ExternalLedgerRaw.class) {
                return List.copyOf(bybitRawSessions);
            }
            if (entityOrCollection == DzengiExtractedEvent.class) {
                return List.copyOf(dzengiExtractedSessions);
            }
            return List.of();
        }

        List<String> resolve(Query query, String field, Object entityOrCollection) {
            if ("walletAddress".equals(field)) {
                if (entityOrCollection instanceof Class<?> entityClass) {
                    return resolveWalletAddresses(query, entityClass);
                }
                if (entityOrCollection instanceof String collection) {
                    return resolveWalletAddresses(query, collection);
                }
            }
            if ("sessionId".equals(field) && "on_chain_balances".equals(entityOrCollection)) {
                return List.copyOf(balanceSessions);
            }
            if ("accountingUniverseId".equals(field) && "asset_ledger_points".equals(entityOrCollection)) {
                return List.copyOf(ledgerUniverses);
            }
            return List.of();
        }

        private static String queryJson(Query query) {
            return query == null || query.getQueryObject() == null ? "" : query.getQueryObject().toJson();
        }
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
