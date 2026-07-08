package com.walletradar.application.backfill.job;

import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.platform.networks.BlockHeightResolver;
import com.walletradar.platform.networks.BlockTimestampResolver;
import com.walletradar.platform.networks.NetworkAdapter;
import com.walletradar.application.backfill.config.BackfillProperties;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.application.backfill.sync.progress.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.walletradar.domain.sync.SyncStatus.SyncStatusValue;

/**
 * Orchestrates source-level backfill queueing, worker loops, startup resume,
 * and retry handling for already-planned segments.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillJobRunner {

    private record BackfillWorkItem(String walletAddress, NetworkId networkId) {}

    private final BlockingQueue<BackfillWorkItem> backfillQueue = new LinkedBlockingQueue<>();
    private final Set<String> inFlightItems = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean workersStarted = new AtomicBoolean(false);
    private final AtomicBoolean integrationSegmentsRunning = new AtomicBoolean(false);

    private final List<NetworkAdapter> networkAdapters;
    private final List<BlockHeightResolver> blockHeightResolvers;
    private final List<BlockTimestampResolver> blockTimestampResolvers;
    private final BackfillNetworkExecutor backfillNetworkExecutor;
    private final BackfillProperties backfillProperties;
    private final SyncProgressTracker syncProgressTracker;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final BackfillJobPlanner backfillJobPlanner;
    private final List<BackfillSegmentExecutor> backfillSegmentExecutors;
    @Qualifier("backfill-coordinator-executor")
    private final Executor backfillCoordinatorExecutor;
    @Qualifier("backfill-executor")
    private final Executor backfillExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startWorkersIfNeeded();
        dispatchPendingOnChainBackfills();
    }

    void runBackfill(String walletAddress, List<NetworkId> networks) {
        enqueueWork(walletAddress, networks);
    }

    void enqueueWork(String walletAddress, List<NetworkId> networks) {
        for (NetworkId n : networks) {
            enqueueItemIfSupported(new BackfillWorkItem(walletAddress, n));
        }
    }

    private boolean enqueueItemIfSupported(BackfillWorkItem item) {
        String key = itemKey(item.walletAddress(), item.networkId());
        if (!inFlightItems.add(key)) {
            log.debug("Skipping enqueue for {} {} — already in-flight", item.walletAddress(), item.networkId());
            return false;
        }
        NetworkAdapter adapter = findAdapter(item.networkId());
        BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
        BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
        if (adapter == null || heightResolver == null || timestampResolver == null) {
            log.info("Skipping backfill for {} (adapter/block resolver not available)", item.networkId());
            inFlightItems.remove(key);
            syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
            return false;
        }
        backfillQueue.offer(item);
        return true;
    }

    private static String itemKey(String walletAddress, NetworkId networkId) {
        return walletAddress + ":" + networkId.name();
    }

    @Scheduled(fixedDelayString = "${walletradar.ingestion.backfill.dispatch-interval-ms:5000}")
    public void dispatchPendingOnChainBackfills() {
        startWorkersIfNeeded();
        List<SyncStatus> incomplete = syncStatusRepository.findOnChainByStatusIn(
                SyncStatus.SourceKind.ONCHAIN,
                Set.of(SyncStatusValue.PENDING, SyncStatusValue.RUNNING, SyncStatusValue.FAILED)
        );
        if (incomplete.isEmpty()) {
            return;
        }
        int enqueued = 0;
        for (SyncStatus status : incomplete) {
            if (status.getWalletAddress() == null || status.getNetworkId() == null || status.getId() == null) {
                continue;
            }
            if (!hasSegmentsForCurrentWindow(status)) {
                if (!repairMissingCurrentWindowSegments(status)) {
                    continue;
                }
            }
            try {
                BackfillWorkItem item = new BackfillWorkItem(status.getWalletAddress(), NetworkId.valueOf(status.getNetworkId()));
                if (enqueueItemIfSupported(item)) {
                    enqueued++;
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown networks remain skipped until config supports them.
            }
        }
        if (enqueued > 0) {
            log.info("On-chain backfill dispatch enqueued {} work item(s)", enqueued);
        }
    }

    private void startWorkersIfNeeded() {
        if (!workersStarted.compareAndSet(false, true)) return;
        int n = Math.max(1, backfillProperties.getWorkerThreads());
        for (int i = 0; i < n; i++) {
            backfillExecutor.execute(this::workerLoop);
        }
        log.info("Backfill worker loops started: {}", n);
    }

    private void workerLoop() {
        while (true) {
            try {
                BackfillWorkItem item = backfillQueue.take();
                try {
                    NetworkAdapter adapter = findAdapter(item.networkId());
                    BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
                    BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
                    if (adapter == null || heightResolver == null || timestampResolver == null) {
                        syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
                        continue;
                    }
                    backfillNetworkExecutor.runBackfillForNetwork(
                            item.walletAddress(), item.networkId(), adapter, heightResolver, timestampResolver);
                } finally {
                    inFlightItems.remove(itemKey(item.walletAddress(), item.networkId()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Backfill worker interrupted");
                break;
            }
        }
    }

    public boolean isIdle() {
        return backfillQueue.isEmpty() && inFlightItems.isEmpty();
    }

    @Scheduled(fixedDelayString = "${walletradar.integration.backfill.poll-interval-ms:15000}")
    public void processPendingIntegrationSegments() {
        if (!integrationSegmentsRunning.compareAndSet(false, true)) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            requeueStaleRunningIntegrationSegments();
            List<BackfillSegment> candidates = backfillSegmentRepository.findBySourceKindAndStatusInOrderByUpdatedAtAsc(
                    BackfillSegment.SourceKind.INTEGRATION,
                    Set.of(BackfillSegment.SegmentStatus.PENDING, BackfillSegment.SegmentStatus.FAILED)
            );
            int processed = 0;
            for (BackfillSegment segment : candidates) {
                BackfillSegmentExecutor executor = backfillSegmentExecutors.stream()
                        .filter(candidate -> candidate.supports(segment))
                        .findFirst()
                        .orElse(null);
                if (executor == null) {
                    log.warn(
                            "No backfill segment executor registered: segmentId={}, sourceKind={}, provider={}, stream={}",
                            segment.getId(),
                            segment.getSourceKind(),
                            segment.getProvider(),
                            segment.getStream()
                    );
                    continue;
                }
                executor.execute(segment);
                processed++;
            }
            log.debug(
                    "IntegrationBackfillDispatch finished: candidates={}, processed={}, durationMs={}",
                    candidates.size(),
                    processed,
                    System.currentTimeMillis() - startedAt
            );
        } finally {
            integrationSegmentsRunning.set(false);
        }
    }

    private void requeueStaleRunningIntegrationSegments() {
        Instant staleBefore = Instant.now().minusMillis(backfillProperties.getRetrySchedulerIntervalMs());
        List<BackfillSegment> runningSegments = backfillSegmentRepository.findBySourceKindAndStatusInOrderByUpdatedAtAsc(
                BackfillSegment.SourceKind.INTEGRATION,
                Set.of(BackfillSegment.SegmentStatus.RUNNING)
        );
        int requeued = 0;
        for (BackfillSegment segment : runningSegments) {
            if (segment.getUpdatedAt() == null || !segment.getUpdatedAt().isBefore(staleBefore)) {
                continue;
            }
            segment.setStatus(BackfillSegment.SegmentStatus.PENDING);
            segment.setStartedAt(null);
            segment.setUpdatedAt(Instant.now());
            backfillSegmentRepository.save(segment);
            requeued++;
        }
        if (requeued > 0) {
            log.info("Requeued stale integration RUNNING segments: {}", requeued);
        }
    }

    @Scheduled(fixedDelayString = "${walletradar.ingestion.backfill.retry-scheduler-interval-ms:120000}")
    public void retryFailedBackfills() {
        long startedAt = System.currentTimeMillis();
        log.info("BackfillRetryJob started");
        try {
            Instant now = Instant.now();
            int maxRetries = backfillProperties.getMaxRetries();

            List<SyncStatus> failed = syncStatusRepository.findOnChainByStatusIn(
                    SyncStatus.SourceKind.ONCHAIN,
                    Set.of(SyncStatusValue.FAILED, SyncStatusValue.RUNNING)
            );
            int enqueued = 0;
            for (SyncStatus s : failed) {
                if (s.getWalletAddress() == null || s.getNetworkId() == null) continue;
                boolean segmentMode = hasSegmentsForCurrentWindow(s);

                if (s.getStatus() == SyncStatusValue.RUNNING) {
                    if (!segmentMode) {
                        continue;
                    }
                    try {
                        NetworkId networkId = NetworkId.valueOf(s.getNetworkId());
                        BackfillWorkItem item = new BackfillWorkItem(s.getWalletAddress(), networkId);
                        if (enqueueItemIfSupported(item)) {
                            enqueued++;
                        }
                    } catch (IllegalArgumentException ignored) { /* unknown network */ }
                    continue;
                }

                if (s.getRetryCount() >= maxRetries) {
                    if (!segmentMode) {
                        s.setStatus(SyncStatusValue.ABANDONED);
                        s.setSyncBannerMessage("Abandoned after " + maxRetries + " retries");
                        s.setUpdatedAt(now);
                        syncStatusRepository.save(s);
                        log.info("Backfill ABANDONED for {} on {} after {} retries", s.getWalletAddress(), s.getNetworkId(), maxRetries);
                        continue;
                    }
                }

                if (s.getNextRetryAfter() != null && now.isBefore(s.getNextRetryAfter())) {
                    continue;
                }

                try {
                    NetworkId networkId = NetworkId.valueOf(s.getNetworkId());
                    BackfillWorkItem item = new BackfillWorkItem(s.getWalletAddress(), networkId);
                    if (enqueueItemIfSupported(item)) {
                        enqueued++;
                    }
                } catch (IllegalArgumentException ignored) { /* unknown network */ }
            }
            if (enqueued > 0) {
                log.info("Retry scheduler: re-enqueued {} failed backfill(s)", enqueued);
            }
            log.info("BackfillRetryJob finished: failedOrRunningChecked={}, reEnqueued={}, durationMs={}",
                    failed.size(), enqueued, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("BackfillRetryJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }

    private NetworkAdapter findAdapter(NetworkId networkId) {
        return networkAdapters.stream().filter(a -> a.supports(networkId)).findFirst().orElse(null);
    }

    private BlockHeightResolver findBlockHeightResolver(NetworkId networkId) {
        return blockHeightResolvers.stream().filter(r -> r.supports(networkId)).findFirst().orElse(null);
    }

    private BlockTimestampResolver findBlockTimestampResolver(NetworkId networkId) {
        return blockTimestampResolvers.stream().filter(r -> r.supports(networkId)).findFirst().orElse(null);
    }

    private boolean repairMissingCurrentWindowSegments(SyncStatus status) {
        if (status == null || status.getId() == null || status.getStatus() != SyncStatus.SyncStatusValue.PENDING) {
            return false;
        }
        int plannedSegments = backfillJobPlanner.planOnChainSyncStatus(status.getId());
        if (plannedSegments > 0 && hasSegmentsForCurrentWindow(status)) {
            log.info(
                    "Recovered missing current-window segments: wallet={}, network={}, syncStatusId={}, segments={}",
                    status.getWalletAddress(),
                    status.getNetworkId(),
                    status.getId(),
                    plannedSegments
            );
            return true;
        }
        log.warn(
                "Skipping pending on-chain sync without current-window segments: wallet={}, network={}, syncStatusId={}, fromBlock={}, toBlock={}",
                status.getWalletAddress(),
                status.getNetworkId(),
                status.getId(),
                status.getWindowFromBlock(),
                status.getWindowToBlock()
        );
        return false;
    }

    private boolean hasSegmentsForCurrentWindow(SyncStatus status) {
        if (status == null || status.getId() == null) {
            return false;
        }
        List<BackfillSegment> segments = backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(status.getId());
        if (segments.isEmpty()) {
            return false;
        }
        if (status.getWindowFromBlock() == null || status.getWindowToBlock() == null) {
            return true;
        }
        Long minFrom = segments.stream()
                .map(BackfillSegment::getFromBlock)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Long maxTo = segments.stream()
                .map(BackfillSegment::getToBlock)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Objects.equals(minFrom, status.getWindowFromBlock())
                && Objects.equals(maxTo, status.getWindowToBlock());
    }
}
