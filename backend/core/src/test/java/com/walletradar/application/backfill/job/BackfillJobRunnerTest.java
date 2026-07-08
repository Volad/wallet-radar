package com.walletradar.application.backfill.job;

import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.platform.networks.BlockHeightResolver;
import com.walletradar.platform.networks.BlockTimestampResolver;
import com.walletradar.platform.networks.NetworkAdapter;
import com.walletradar.application.backfill.config.BackfillProperties;
import com.walletradar.application.backfill.sync.progress.SyncProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackfillJobRunnerTest {

    @Mock private NetworkAdapter networkAdapter;
    @Mock private BlockHeightResolver blockHeightResolver;
    @Mock private BlockTimestampResolver blockTimestampResolver;
    @Mock private BackfillNetworkExecutor backfillNetworkExecutor;
    @Mock private BackfillProperties backfillProperties;
    @Mock private SyncProgressTracker syncProgressTracker;
    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private BackfillSegmentRepository backfillSegmentRepository;
    @Mock private BackfillJobPlanner backfillJobPlanner;
    @Mock private BackfillSegmentExecutor backfillSegmentExecutor;

    private BackfillJobRunner runner;

    @BeforeEach
    void setUp() {
        Executor direct = Runnable::run;
        runner = new BackfillJobRunner(
                List.of(networkAdapter),
                List.of(blockHeightResolver),
                List.of(blockTimestampResolver),
                backfillNetworkExecutor,
                backfillProperties,
                syncProgressTracker,
                syncStatusRepository,
                backfillSegmentRepository,
                backfillJobPlanner,
                List.of(backfillSegmentExecutor),
                direct,
                direct
        );

        when(backfillProperties.getMaxRetries()).thenReturn(3);
        when(networkAdapter.supports(NetworkId.ETHEREUM)).thenReturn(true);
        when(blockHeightResolver.supports(NetworkId.ETHEREUM)).thenReturn(true);
        when(blockTimestampResolver.supports(NetworkId.ETHEREUM)).thenReturn(true);
    }

    @Test
    @DisplayName("segment mode does not move FAILED sync_status to ABANDONED even after max retries")
    void segmentModeNeverAbandoned() {
        SyncStatus failed = new SyncStatus();
        failed.setId("sync-1");
        failed.setWalletAddress("0xWALLET");
        failed.setNetworkId("ETHEREUM");
        failed.setStatus(SyncStatus.SyncStatusValue.FAILED);
        failed.setRetryCount(10);

        when(syncStatusRepository.findOnChainByStatusIn(
                SyncStatus.SourceKind.ONCHAIN,
                Set.of(SyncStatus.SyncStatusValue.FAILED, SyncStatus.SyncStatusValue.RUNNING)
        ))
                .thenReturn(List.of(failed));
        BackfillSegment completed = new BackfillSegment();
        completed.setSyncStatusId("sync-1");
        completed.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-1"))
                .thenReturn(List.of(completed));

        runner.retryFailedBackfills();

        verify(syncStatusRepository, never()).save(any(SyncStatus.class));
        assertThat(runner.isIdle()).isFalse();
    }

    @Test
    @DisplayName("legacy mode moves FAILED sync_status to ABANDONED after max retries")
    void legacyModeAbandonedAfterMaxRetries() {
        SyncStatus failed = new SyncStatus();
        failed.setId("sync-2");
        failed.setWalletAddress("0xWALLET");
        failed.setNetworkId("ETHEREUM");
        failed.setStatus(SyncStatus.SyncStatusValue.FAILED);
        failed.setRetryCount(10);

        when(syncStatusRepository.findOnChainByStatusIn(
                SyncStatus.SourceKind.ONCHAIN,
                Set.of(SyncStatus.SyncStatusValue.FAILED, SyncStatus.SyncStatusValue.RUNNING)
        ))
                .thenReturn(List.of(failed));
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-2"))
                .thenReturn(List.of());

        runner.retryFailedBackfills();

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncStatus.SyncStatusValue.ABANDONED);
    }

    @Test
    @DisplayName("integration segments are dispatched through shared backfill runner")
    void dispatchesIntegrationSegmentsThroughRegisteredExecutor() {
        BackfillSegment segment = new BackfillSegment();
        segment.setId("seg-1");
        segment.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);
        segment.setProvider("BYBIT");
        segment.setStream("TRANSACTION_LOG");
        segment.setStatus(BackfillSegment.SegmentStatus.PENDING);

        when(backfillSegmentRepository.findBySourceKindAndStatusInOrderByUpdatedAtAsc(
                eq(BackfillSegment.SourceKind.INTEGRATION),
                any(Set.class)
        )).thenReturn(List.of(segment));
        when(backfillSegmentExecutor.supports(segment)).thenReturn(true);

        runner.processPendingIntegrationSegments();

        verify(backfillSegmentExecutor).execute(segment);
    }

    @Test
    @DisplayName("stale integration RUNNING segments are requeued back to PENDING")
    void requeuesStaleRunningIntegrationSegments() {
        BackfillSegment staleRunning = new BackfillSegment();
        staleRunning.setId("seg-stale");
        staleRunning.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);
        staleRunning.setStatus(BackfillSegment.SegmentStatus.RUNNING);
        staleRunning.setUpdatedAt(Instant.now().minusSeconds(300));

        when(backfillSegmentRepository.findBySourceKindAndStatusInOrderByUpdatedAtAsc(
                eq(BackfillSegment.SourceKind.INTEGRATION),
                eq(Set.of(BackfillSegment.SegmentStatus.RUNNING))
        )).thenReturn(List.of(staleRunning));
        when(backfillSegmentRepository.findBySourceKindAndStatusInOrderByUpdatedAtAsc(
                eq(BackfillSegment.SourceKind.INTEGRATION),
                eq(Set.of(BackfillSegment.SegmentStatus.PENDING, BackfillSegment.SegmentStatus.FAILED))
        )).thenReturn(List.of());

        runner.processPendingIntegrationSegments();

        ArgumentCaptor<BackfillSegment> captor = ArgumentCaptor.forClass(BackfillSegment.class);
        verify(backfillSegmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BackfillSegment.SegmentStatus.PENDING);
        assertThat(captor.getValue().getStartedAt()).isNull();
    }

    @Test
    @DisplayName("dispatch replans pending on-chain sync when current-window segments are missing")
    void dispatchReplansPendingOnChainSyncWhenCurrentWindowSegmentsAreMissing() {
        Executor noop = command -> { };
        BackfillJobRunner dispatchRunner = new BackfillJobRunner(
                List.of(networkAdapter),
                List.of(blockHeightResolver),
                List.of(blockTimestampResolver),
                backfillNetworkExecutor,
                backfillProperties,
                syncProgressTracker,
                syncStatusRepository,
                backfillSegmentRepository,
                backfillJobPlanner,
                List.of(backfillSegmentExecutor),
                noop,
                noop
        );

        SyncStatus pending = new SyncStatus();
        pending.setId("sync-3");
        pending.setWalletAddress("0xWALLET");
        pending.setNetworkId("ETHEREUM");
        pending.setStatus(SyncStatus.SyncStatusValue.PENDING);
        pending.setWindowFromBlock(101L);
        pending.setWindowToBlock(120L);

        BackfillSegment staleSegment = new BackfillSegment();
        staleSegment.setId("sync-3:0");
        staleSegment.setSyncStatusId("sync-3");
        staleSegment.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
        staleSegment.setFromBlock(1L);
        staleSegment.setToBlock(100L);

        BackfillSegment currentSegment = new BackfillSegment();
        currentSegment.setId("sync-3:1");
        currentSegment.setSyncStatusId("sync-3");
        currentSegment.setStatus(BackfillSegment.SegmentStatus.PENDING);
        currentSegment.setFromBlock(101L);
        currentSegment.setToBlock(120L);

        when(syncStatusRepository.findOnChainByStatusIn(
                SyncStatus.SourceKind.ONCHAIN,
                Set.of(SyncStatus.SyncStatusValue.PENDING, SyncStatus.SyncStatusValue.RUNNING, SyncStatus.SyncStatusValue.FAILED)
        )).thenReturn(List.of(pending));
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-3"))
                .thenReturn(List.of(staleSegment), List.of(currentSegment), List.of(currentSegment));
        when(backfillJobPlanner.planOnChainSyncStatus("sync-3")).thenReturn(1);

        dispatchRunner.dispatchPendingOnChainBackfills();

        verify(backfillJobPlanner).planOnChainSyncStatus("sync-3");
        assertThat(dispatchRunner.isIdle()).isFalse();
    }
}
