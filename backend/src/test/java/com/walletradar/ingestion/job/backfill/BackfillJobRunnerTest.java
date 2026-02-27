package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.BackfillSegmentRepository;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.classifier.InternalTransferReclassifier;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private InternalTransferReclassifier internalTransferReclassifier;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private BackfillSegmentRepository backfillSegmentRepository;

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
                internalTransferReclassifier,
                applicationEventPublisher,
                syncStatusRepository,
                backfillSegmentRepository,
                direct,
                direct
        );

        when(backfillProperties.getMaxRetries()).thenReturn(3);
        when(networkAdapter.supports(NetworkId.ETHEREUM)).thenReturn(true);
        when(blockHeightResolver.supports(NetworkId.ETHEREUM)).thenReturn(true);
        when(blockTimestampResolver.supports(NetworkId.ETHEREUM)).thenReturn(true);
        when(internalTransferReclassifier.reclassify(any(Set.class))).thenReturn(List.of());
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

        when(syncStatusRepository.findByStatusIn(Set.of(SyncStatus.SyncStatusValue.FAILED, SyncStatus.SyncStatusValue.RUNNING)))
                .thenReturn(List.of(failed));
        when(backfillSegmentRepository.existsBySyncStatusId("sync-1")).thenReturn(true);

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

        when(syncStatusRepository.findByStatusIn(Set.of(SyncStatus.SyncStatusValue.FAILED, SyncStatus.SyncStatusValue.RUNNING)))
                .thenReturn(List.of(failed));
        when(backfillSegmentRepository.existsBySyncStatusId("sync-2")).thenReturn(false);

        runner.retryFailedBackfills();

        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncStatus.SyncStatusValue.ABANDONED);
    }
}
