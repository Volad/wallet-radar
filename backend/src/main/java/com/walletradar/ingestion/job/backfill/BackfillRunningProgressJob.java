package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.BackfillSegment;
import com.walletradar.domain.BackfillSegmentRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Recomputes sync_status RUNNING progress from persisted backfill_segments.
 * progressPct = average(progressPct of all segments for the sync).
 */
@Component
@RequiredArgsConstructor
public class BackfillRunningProgressJob {

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final SyncProgressTracker syncProgressTracker;

    @Scheduled(fixedDelayString = "${walletradar.ingestion.backfill.progress-update-interval-ms:2000}")
    public void updateRunningSyncProgress() {
        List<SyncStatus> running = syncStatusRepository.findByStatusIn(Set.of(SyncStatus.SyncStatusValue.RUNNING));
        for (SyncStatus sync : running) {
            if (sync.getId() == null || sync.getWalletAddress() == null || sync.getNetworkId() == null) {
                continue;
            }
            if (!backfillSegmentRepository.existsBySyncStatusId(sync.getId())) {
                continue;
            }
            List<BackfillSegment> segments = backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(sync.getId());
            if (segments.isEmpty()) {
                continue;
            }

            int progressPct = averageSegmentProgressPct(segments);
            long total = segments.size();
            long complete = segments.stream().filter(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE).count();
            Long lastBlock = segments.stream()
                    .filter(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                    .map(BackfillSegment::getToBlock)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            syncProgressTracker.setRunning(
                    sync.getWalletAddress(),
                    sync.getNetworkId(),
                    progressPct,
                    lastBlock,
                    "Raw fetch " + sync.getNetworkId() + ": " + complete + "/" + total + " segments complete"
            );
        }
    }

    private int averageSegmentProgressPct(List<BackfillSegment> segments) {
        int total = segments.stream()
                .map(BackfillSegment::getProgressPct)
                .mapToInt(p -> p == null ? 0 : Math.max(0, Math.min(100, p)))
                .sum();
        return Math.max(0, Math.min(100, total / segments.size()));
    }
}
