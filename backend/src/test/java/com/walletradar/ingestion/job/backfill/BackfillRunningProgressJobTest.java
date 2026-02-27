package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.BackfillSegment;
import com.walletradar.domain.BackfillSegmentRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackfillRunningProgressJobTest {

    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private BackfillSegmentRepository backfillSegmentRepository;
    @Mock private SyncProgressTracker syncProgressTracker;

    @Test
    @DisplayName("recalculates RUNNING sync progress as average over all segments")
    void updatesRunningProgressFromAverage() {
        SyncStatus sync = new SyncStatus();
        sync.setId("sync-1");
        sync.setWalletAddress("0xWALLET");
        sync.setNetworkId("ETHEREUM");
        sync.setStatus(SyncStatus.SyncStatusValue.RUNNING);

        BackfillSegment s1 = segment("sync-1:0", "sync-1", 0, 1L, 10L,
                BackfillSegment.SegmentStatus.COMPLETE, 100, 10L);
        BackfillSegment s2 = segment("sync-1:1", "sync-1", 1, 11L, 20L,
                BackfillSegment.SegmentStatus.RUNNING, 50, 15L);
        BackfillSegment s3 = segment("sync-1:2", "sync-1", 2, 21L, 30L,
                BackfillSegment.SegmentStatus.PENDING, 0, null);

        when(syncStatusRepository.findByStatusIn(Set.of(SyncStatus.SyncStatusValue.RUNNING)))
                .thenReturn(List.of(sync));
        when(backfillSegmentRepository.existsBySyncStatusId("sync-1")).thenReturn(true);
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc("sync-1"))
                .thenReturn(List.of(s1, s2, s3));

        BackfillRunningProgressJob job = new BackfillRunningProgressJob(
                syncStatusRepository,
                backfillSegmentRepository,
                syncProgressTracker
        );

        job.updateRunningSyncProgress();

        ArgumentCaptor<Integer> pctCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(syncProgressTracker).setRunning(
                org.mockito.ArgumentMatchers.eq("0xWALLET"),
                org.mockito.ArgumentMatchers.eq("ETHEREUM"),
                pctCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.contains("1/3 segments complete")
        );
        assertThat(pctCaptor.getValue()).isEqualTo(50);
    }

    @Test
    @DisplayName("does not update non-segment RUNNING sync")
    void skipsNonSegmentRunningSync() {
        SyncStatus sync = new SyncStatus();
        sync.setId("sync-legacy");
        sync.setWalletAddress("0xWALLET");
        sync.setNetworkId("ETHEREUM");
        sync.setStatus(SyncStatus.SyncStatusValue.RUNNING);

        when(syncStatusRepository.findByStatusIn(Set.of(SyncStatus.SyncStatusValue.RUNNING)))
                .thenReturn(List.of(sync));
        when(backfillSegmentRepository.existsBySyncStatusId("sync-legacy")).thenReturn(false);

        BackfillRunningProgressJob job = new BackfillRunningProgressJob(
                syncStatusRepository,
                backfillSegmentRepository,
                syncProgressTracker
        );

        job.updateRunningSyncProgress();

        verify(syncProgressTracker, never()).setRunning(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    private static BackfillSegment segment(String id, String syncStatusId, int index,
                                           long fromBlock, long toBlock,
                                           BackfillSegment.SegmentStatus status,
                                           Integer progressPct,
                                           Long lastProcessedBlock) {
        BackfillSegment s = new BackfillSegment();
        s.setId(id);
        s.setSyncStatusId(syncStatusId);
        s.setSegmentIndex(index);
        s.setFromBlock(fromBlock);
        s.setToBlock(toBlock);
        s.setStatus(status);
        s.setProgressPct(progressPct);
        s.setLastProcessedBlock(lastProcessedBlock);
        return s;
    }
}
