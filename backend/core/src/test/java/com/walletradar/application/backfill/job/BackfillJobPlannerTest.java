package com.walletradar.application.backfill.job;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.application.backfill.config.BackfillProperties;
import com.walletradar.platform.networks.config.IngestionNetworkProperties;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SourceSyncPlanner;
import com.walletradar.domain.sync.BackfillSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackfillJobPlannerTest {

    @Mock
    private SyncStatusRepository syncStatusRepository;
    @Mock
    private BackfillSegmentRepository backfillSegmentRepository;
    @Mock
    private BackfillProperties backfillProperties;
    @Mock
    private IngestionNetworkProperties ingestionNetworkProperties;
    @Mock
    private IntegrationBackfillPlanningService integrationBackfillPlanningService;
    @Mock
    private SourceSyncPlanner sourceSyncPlanner;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    @InjectMocks
    private BackfillJobPlanner backfillJobPlanner;

    @Test
    void skipsOnChainSegmentsWhenBackfillDisabled() {
        UserSession session = new UserSession();
        session.setId("session-1");
        session.setAccountingUniverseId("session-1");
        UserSession.SessionWallet wallet = new UserSession.SessionWallet();
        wallet.setAddress("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG");
        wallet.setNetworks(List.of(NetworkId.SOLANA));
        session.setWallets(List.of(wallet));

        SyncStatus status = new SyncStatus();
        status.setId("sync-solana");
        status.setStatus(SyncStatus.SyncStatusValue.PENDING);
        status.setWalletAddress(wallet.getAddress());
        status.setNetworkId(NetworkId.SOLANA.name());
        status.setWindowFromBlock(1L);
        status.setWindowToBlock(10L);

        when(syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                SyncStatus.SourceKind.ONCHAIN,
                wallet.getAddress(),
                NetworkId.SOLANA.name()
        )).thenReturn(Optional.of(status));
        when(accountingUniverseService.isBackfillEnabled(
                eq("session-1"),
                eq(wallet.getAddress()),
                eq(NetworkId.SOLANA)
        )).thenReturn(false);

        int planned = backfillJobPlanner.planPendingSessionSources(session);

        assertThat(planned).isZero();
        verify(sourceSyncPlanner, never()).repairOnChainBlockWindowIfMissing(any(), any());
        verify(backfillSegmentRepository, never()).saveAll(any());
    }

    @Test
    void solanaTwoYearWindowPlansExactlyOneSegment() {
        SyncStatus status = pendingOnChainStatus(
                "sync-solana", NetworkId.SOLANA, 1L, 165_000_000L);
        when(syncStatusRepository.findById("sync-solana")).thenReturn(Optional.of(status));
        when(accountingUniverseService.isBackfillEnabled(any(), eq(status.getWalletAddress()), eq(NetworkId.SOLANA)))
                .thenReturn(true);
        when(sourceSyncPlanner.repairOnChainBlockWindowIfMissing(eq(status), any())).thenReturn(status);
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-solana"))
                .thenReturn(List.of());

        int planned = backfillJobPlanner.planOnChainSyncStatus("sync-solana");

        assertThat(planned).isEqualTo(1);
        List<BackfillSegment> saved = captureSavedSegments();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getFromBlock()).isEqualTo(1L);
        assertThat(saved.get(0).getToBlock()).isEqualTo(165_000_000L);
    }

    @Test
    void evmNetworkKeepsMultiSegmentPlanning() {
        // Small window (1..600) with default per-segment target of 1 block → many segments,
        // proving EVM segmentation is untouched by the Solana/TON single-segment rule.
        SyncStatus status = pendingOnChainStatus(
                "sync-eth", NetworkId.ETHEREUM, 1L, 600L);
        when(syncStatusRepository.findById("sync-eth")).thenReturn(Optional.of(status));
        when(accountingUniverseService.isBackfillEnabled(any(), eq(status.getWalletAddress()), eq(NetworkId.ETHEREUM)))
                .thenReturn(true);
        when(sourceSyncPlanner.repairOnChainBlockWindowIfMissing(eq(status), any())).thenReturn(status);
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-eth"))
                .thenReturn(List.of());

        int planned = backfillJobPlanner.planOnChainSyncStatus("sync-eth");

        assertThat(planned).isGreaterThan(1);
        List<BackfillSegment> saved = captureSavedSegments();
        assertThat(saved).hasSizeGreaterThan(1);
        assertThat(saved.get(0).getFromBlock()).isEqualTo(1L);
        assertThat(saved.get(saved.size() - 1).getToBlock()).isEqualTo(600L);
    }

    private static SyncStatus pendingOnChainStatus(String id, NetworkId networkId, long fromBlock, long toBlock) {
        SyncStatus status = new SyncStatus();
        status.setId(id);
        status.setStatus(SyncStatus.SyncStatusValue.PENDING);
        status.setWalletAddress("wallet-" + networkId.name());
        status.setNetworkId(networkId.name());
        status.setWindowFromBlock(fromBlock);
        status.setWindowToBlock(toBlock);
        return status;
    }

    @SuppressWarnings("unchecked")
    private List<BackfillSegment> captureSavedSegments() {
        ArgumentCaptor<List<BackfillSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(backfillSegmentRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
