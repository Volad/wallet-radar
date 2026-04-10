package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.wallet.command.WalletBackfillService;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRefreshCommandServiceTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private WalletBackfillService walletBackfillService;
    @Mock
    private IntegrationBackfillPlanningService integrationBackfillPlanningService;
    @Mock
    private SessionPipelineStateService sessionPipelineStateService;
    @Mock
    private BlockHeightResolver blockHeightResolver;

    private SessionRefreshCommandService sessionRefreshCommandService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        sessionRefreshCommandService = new SessionRefreshCommandService(
                userSessionRepository,
                syncStatusRepository,
                backfillSegmentRepository,
                walletBackfillService,
                integrationBackfillPlanningService,
                sessionPipelineStateService,
                List.of(blockHeightResolver)
        );
    }

    @Test
    @DisplayName("refresh schedules only sources with a real delta window")
    void refresh_schedulesDeltaTargets() {
        UserSession session = session("session-1");
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setLastSyncAt(Instant.parse("2026-04-10T09:00:00Z"));
        session.setIntegrations(List.of(integration));

        SyncStatus onChainStatus = new SyncStatus();
        onChainStatus.setWalletAddress("0xabc");
        onChainStatus.setNetworkId(NetworkId.BASE.name());
        onChainStatus.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        onChainStatus.setBackfillComplete(true);
        onChainStatus.setLastBlockSynced(100L);

        SyncStatus integrationStatus = new SyncStatus();
        integrationStatus.setIntegrationId("BYBIT-33625378");
        integrationStatus.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        integrationStatus.setBackfillComplete(true);

        UserSession.IntegrationSyncState syncState = new UserSession.IntegrationSyncState();
        syncState.setTotalSegments(2);
        syncState.setCompletedSegments(0);
        syncState.setFailedSegments(0);
        syncState.setProgressPct(0);

        when(userSessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(SyncStatus.SourceKind.ONCHAIN, "0xabc", NetworkId.BASE.name()))
                .thenReturn(Optional.of(onChainStatus));
        when(syncStatusRepository.findLatestByIntegrationId("BYBIT-33625378"))
                .thenReturn(Optional.of(integrationStatus));
        when(blockHeightResolver.supports(NetworkId.BASE)).thenReturn(true);
        when(blockHeightResolver.getCurrentBlock(NetworkId.BASE)).thenReturn(125L);
        when(integrationBackfillPlanningService.replanIncrementalBackfill(
                eq("session-1"),
                eq(integration),
                eq(Instant.parse("2026-04-10T09:00:00Z")),
                any(Instant.class)
        )).thenReturn(syncState);

        SessionRefreshCommandService.SessionRefreshResult result = sessionRefreshCommandService.refresh("session-1").orElseThrow();

        assertThat(result.status()).isEqualTo(SessionRefreshCommandService.RefreshStatus.SCHEDULED);
        assertThat(result.scheduledTargets()).isEqualTo(2);
        assertThat(result.skippedTargets()).isZero();
        verify(walletBackfillService).scheduleIncrementalBackfill("0xabc", List.of(NetworkId.BASE));
        verify(sessionPipelineStateService).markStageRunning("session-1", UserSession.PipelineStage.BACKFILL, "Incremental refresh queued");
        ArgumentCaptor<UserSession> sessionCaptor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(sessionCaptor.capture());
        UserSession saved = sessionCaptor.getValue();
        assertThat(saved.getIntegrations()).hasSize(1);
        assertThat(saved.getIntegrations().get(0).getStatus()).isEqualTo(UserSession.IntegrationStatus.BACKFILLING);
        assertThat(saved.getIntegrations().get(0).getSyncState()).isSameAs(syncState);
    }

    @Test
    @DisplayName("refresh returns up-to-date when every source checkpoint is current")
    void refresh_returnsUpToDate() {
        UserSession session = session("session-2");

        SyncStatus onChainStatus = new SyncStatus();
        onChainStatus.setWalletAddress("0xabc");
        onChainStatus.setNetworkId(NetworkId.BASE.name());
        onChainStatus.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        onChainStatus.setBackfillComplete(true);
        onChainStatus.setLastBlockSynced(150L);

        when(userSessionRepository.findById("session-2")).thenReturn(Optional.of(session));
        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(SyncStatus.SourceKind.ONCHAIN, "0xabc", NetworkId.BASE.name()))
                .thenReturn(Optional.of(onChainStatus));
        when(blockHeightResolver.supports(NetworkId.BASE)).thenReturn(true);
        when(blockHeightResolver.getCurrentBlock(NetworkId.BASE)).thenReturn(150L);

        SessionRefreshCommandService.SessionRefreshResult result = sessionRefreshCommandService.refresh("session-2").orElseThrow();

        assertThat(result.status()).isEqualTo(SessionRefreshCommandService.RefreshStatus.UP_TO_DATE);
        assertThat(result.scheduledTargets()).isZero();
        assertThat(result.skippedTargets()).isEqualTo(1);
        verify(walletBackfillService, never()).scheduleIncrementalBackfill(any(), any());
        verify(integrationBackfillPlanningService, never()).replanIncrementalBackfill(any(), any(), any(), any());
        verify(userSessionRepository, never()).save(any(UserSession.class));
        verify(sessionPipelineStateService, never()).markStageRunning(any(), any(), any());
    }

    @Test
    @DisplayName("refresh is rejected while the session pipeline is running")
    void refresh_rejectsRunningPipeline() {
        UserSession session = session("session-3");
        UserSession.PipelineState pipelineState = new UserSession.PipelineState();
        pipelineState.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        pipelineState.setStatus(UserSession.PipelineStatus.RUNNING);
        session.setPipelineState(pipelineState);

        when(userSessionRepository.findById("session-3")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionRefreshCommandService.refresh("session-3"))
                .isInstanceOf(SessionRefreshCommandService.RefreshConflictException.class)
                .hasMessage("Refresh is unavailable while the pipeline is running");
    }

    private static UserSession session(String sessionId) {
        UserSession session = new UserSession();
        session.setId(sessionId);
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xabc");
        wallet.setLabel("Wallet 1");
        wallet.setColor("#22d3ee");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));
        UserSession.PipelineState pipelineState = new UserSession.PipelineState();
        pipelineState.setStage(UserSession.PipelineStage.ACCOUNTING_REPLAY);
        pipelineState.setStatus(UserSession.PipelineStatus.COMPLETE);
        session.setPipelineState(pipelineState);
        return session;
    }
}
