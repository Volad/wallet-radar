package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceSyncPlannerTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private BlockHeightResolver blockHeightResolver;

    private SourceSyncPlanner sourceSyncPlanner;

    @BeforeEach
    void setUp() {
        BackfillProperties backfillProperties = new BackfillProperties();
        backfillProperties.setWindowBlocks(5_500_000L);

        IngestionNetworkProperties ingestionNetworkProperties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry base = new IngestionNetworkProperties.NetworkIngestionEntry();
        base.setWindowBlocks(5_500_000L);
        ingestionNetworkProperties.setNetwork(Map.of(NetworkId.BASE.name(), base));

        IntegrationBackfillProperties integrationBackfillProperties = new IntegrationBackfillProperties();
        integrationBackfillProperties.setHistoryYears(2);

        sourceSyncPlanner = new SourceSyncPlanner(
                syncStatusRepository,
                backfillSegmentRepository,
                List.of(blockHeightResolver),
                backfillProperties,
                ingestionNetworkProperties,
                integrationBackfillProperties
        );

        when(syncStatusRepository.save(any(SyncStatus.class)))
                .thenAnswer(invocation -> {
                    SyncStatus saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId("generated-sync-status-id");
                    }
                    return saved;
                });
    }

    @Test
    @DisplayName("refresh uses the latest completed on-chain segment checkpoint when sync_status is stale")
    void planRefresh_usesCompletedOnChainSegmentCheckpoint() {
        UserSession session = sessionWithWallet();
        SyncStatus status = new SyncStatus();
        status.setId("sync-onchain-1");
        status.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        status.setWalletAddress("0xabc");
        status.setNetworkId(NetworkId.BASE.name());
        status.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        status.setBackfillComplete(true);
        status.setLastBlockSynced(100L);

        BackfillSegment completed = new BackfillSegment();
        completed.setSyncStatusId("sync-onchain-1");
        completed.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        completed.setToBlock(120L);

        BackfillSegment failed = new BackfillSegment();
        failed.setSyncStatusId("sync-onchain-1");
        failed.setStatus(BackfillSegment.SegmentStatus.FAILED);
        failed.setToBlock(140L);

        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN,
                "0xabc",
                NetworkId.BASE.name()
        )).thenReturn(Optional.of(status));
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-onchain-1"))
                .thenReturn(List.of(completed, failed));
        when(blockHeightResolver.supports(NetworkId.BASE)).thenReturn(true);
        when(blockHeightResolver.getCurrentBlock(NetworkId.BASE)).thenReturn(150L);

        SourceSyncPlanner.PlanResult result = sourceSyncPlanner.planRefresh(
                session,
                Instant.parse("2026-04-12T10:30:45Z")
        );

        assertThat(result.scheduledTargets()).isEqualTo(1);
        assertThat(result.skippedTargets()).isZero();
        assertThat(result.scheduledOnChainSyncStatusIds()).containsExactly("sync-onchain-1");

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getWindowFromBlock()).isEqualTo(121L);
        assertThat(saved.getWindowToBlock()).isEqualTo(150L);
        assertThat(saved.getSyncBannerMessage()).isEqualTo("Refresh queued");
    }

    @Test
    @DisplayName("refresh uses the latest completed integration segment checkpoint when sync_status is stale")
    void planRefresh_usesCompletedIntegrationSegmentCheckpoint() {
        UserSession session = sessionWithIntegration();
        UserSession.SessionIntegration integration = session.getIntegrations().get(0);

        SyncStatus status = new SyncStatus();
        status.setId("sync-integration-1");
        status.setSourceKind(SyncStatus.SourceKind.INTEGRATION);
        status.setIntegrationId(integration.getIntegrationId());
        status.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        status.setBackfillComplete(true);
        status.setLastSyncedAt(Instant.parse("2026-04-10T08:00:00Z"));

        BackfillSegment completed = new BackfillSegment();
        completed.setIntegrationId(integration.getIntegrationId());
        completed.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        completed.setToTime(Instant.parse("2026-04-10T09:00:00Z"));

        BackfillSegment failed = new BackfillSegment();
        failed.setIntegrationId(integration.getIntegrationId());
        failed.setStatus(BackfillSegment.SegmentStatus.FAILED);
        failed.setToTime(Instant.parse("2026-04-10T12:00:00Z"));

        when(syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()))
                .thenReturn(Optional.of(status));
        when(backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(integration.getIntegrationId()))
                .thenReturn(List.of(completed, failed));

        SourceSyncPlanner.PlanResult result = sourceSyncPlanner.planRefresh(
                session,
                Instant.parse("2026-04-12T10:30:45Z")
        );

        assertThat(result.scheduledTargets()).isEqualTo(1);
        assertThat(result.skippedTargets()).isZero();
        assertThat(result.scheduledIntegrationSyncStatusIds()).containsExactly("sync-integration-1");
        assertThat(integration.getStatus()).isEqualTo(UserSession.IntegrationStatus.BACKFILLING);
        assertThat(integration.getSyncState()).isNotNull();
        assertThat(integration.getSyncState().getTotalSegments()).isZero();

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getWindowFromTime()).isEqualTo(Instant.parse("2026-04-10T09:00:00Z"));
        assertThat(saved.getWindowToTime()).isEqualTo(Instant.parse("2026-04-12T10:30:45Z"));
        assertThat(saved.getSyncBannerMessage()).isEqualTo("Refresh queued");
    }

    @Test
    @DisplayName("refresh creates integration sync window from integration checkpoint when sync_status is absent")
    void planRefresh_createsIntegrationWindowWithoutLegacySyncStatusRow() {
        UserSession session = sessionWithIntegration();
        UserSession.SessionIntegration integration = session.getIntegrations().get(0);
        integration.setLastSyncAt(Instant.parse("2026-04-10T09:00:00Z"));

        when(syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()))
                .thenReturn(Optional.empty());
        when(backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(integration.getIntegrationId()))
                .thenReturn(List.of());

        SourceSyncPlanner.PlanResult result = sourceSyncPlanner.planRefresh(
                session,
                Instant.parse("2026-04-12T10:30:45Z")
        );

        assertThat(result.scheduledTargets()).isEqualTo(1);
        assertThat(result.skippedTargets()).isZero();
        assertThat(result.scheduledIntegrationSyncStatusIds()).hasSize(1);
        assertThat(integration.getStatus()).isEqualTo(UserSession.IntegrationStatus.BACKFILLING);

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        SyncStatus saved = captor.getValue();
        assertThat(saved.getWindowFromTime()).isEqualTo(Instant.parse("2026-04-10T09:00:00Z"));
        assertThat(saved.getWindowToTime()).isEqualTo(Instant.parse("2026-04-12T10:30:45Z"));
        assertThat(saved.getSyncBannerMessage()).isEqualTo("Refresh queued");
    }

    @Test
    @DisplayName("refresh isolates an unresolvable network so healthy networks still get armed")
    void planRefresh_isolatesUnresolvableNetwork() {
        UserSession session = new UserSession();
        session.setId("session-multi");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xabc");
        wallet.setNetworks(List.of(NetworkId.POLYGON, NetworkId.BASE));
        session.setWallets(List.of(wallet));
        session.setIntegrations(List.of());

        SyncStatus polygonStatus = new SyncStatus();
        polygonStatus.setId("sync-polygon");
        polygonStatus.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        polygonStatus.setWalletAddress("0xabc");
        polygonStatus.setNetworkId(NetworkId.POLYGON.name());
        polygonStatus.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        polygonStatus.setBackfillComplete(true);
        polygonStatus.setLastBlockSynced(100L);

        SyncStatus baseStatus = new SyncStatus();
        baseStatus.setId("sync-base");
        baseStatus.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        baseStatus.setWalletAddress("0xabc");
        baseStatus.setNetworkId(NetworkId.BASE.name());
        baseStatus.setStatus(SyncStatus.SyncStatusValue.COMPLETE);
        baseStatus.setBackfillComplete(true);
        baseStatus.setLastBlockSynced(100L);

        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN, "0xabc", NetworkId.POLYGON.name()))
                .thenReturn(Optional.of(polygonStatus));
        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN, "0xabc", NetworkId.BASE.name()))
                .thenReturn(Optional.of(baseStatus));
        when(blockHeightResolver.supports(any())).thenReturn(true);
        when(blockHeightResolver.getCurrentBlock(NetworkId.POLYGON))
                .thenThrow(new RuntimeException("eth_blockNumber 401 Unauthorized"));
        when(blockHeightResolver.getCurrentBlock(NetworkId.BASE)).thenReturn(150L);

        SourceSyncPlanner.PlanResult result = sourceSyncPlanner.planRefresh(
                session,
                Instant.parse("2026-04-12T10:30:45Z")
        );

        assertThat(result.scheduledTargets()).isEqualTo(1);
        assertThat(result.skippedTargets()).isEqualTo(1);
        assertThat(result.scheduledOnChainSyncStatusIds()).containsExactly("sync-base");
    }

    private static UserSession sessionWithWallet() {
        UserSession session = new UserSession();
        session.setId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("0xabc");
        wallet.setNetworks(List.of(NetworkId.BASE));
        session.setWallets(List.of(wallet));
        session.setIntegrations(List.of());
        return session;
    }

    private static UserSession sessionWithIntegration() {
        UserSession session = new UserSession();
        session.setId("session-2");
        session.setWallets(List.of());
        UserSession.SessionIntegration integration = new UserSession.SessionIntegration();
        integration.setIntegrationId("BYBIT-33625378");
        integration.setProvider(UserSession.IntegrationProvider.BYBIT);
        integration.setStatus(UserSession.IntegrationStatus.READY);
        integration.setAccountRef("BYBIT:33625378");
        session.setIntegrations(List.of(integration));
        return session;
    }
}
