package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.config.BackfillSegmentConfiguration;
import com.walletradar.ingestion.config.BackfillSegmentsConfiguration;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs raw backfill for one wallet-network pair.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillNetworkExecutor {

    private final RawFetchSegmentProcessor rawFetchSegmentProcessor;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final SyncProgressTracker syncProgressTracker;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;

    private static final int MAX_SEGMENTS = 1000;
    private static final Set<BackfillSegment.SegmentStatus> EXECUTABLE_STATUSES = Set.of(
            BackfillSegment.SegmentStatus.PENDING,
            BackfillSegment.SegmentStatus.FAILED
    );

    public void runBackfillForNetwork(String walletAddress, NetworkId networkId,
                                      NetworkAdapter adapter, BlockHeightResolver heightResolver,
                                      BlockTimestampResolver timestampResolver) {
        String networkIdStr = networkId.name();
        try {
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, null, "Starting " + networkIdStr + "...");
            SegmentExecutionConfig segmentConfig = resolveSegmentExecutionConfig(networkIdStr);
            SyncStatus syncStatus = syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkIdStr)
                    .orElseThrow(() -> new IllegalStateException("sync_status not found for " + walletAddress + " " + networkIdStr));

            long toBlock = heightResolver.getCurrentBlock(networkId);
            long windowBlocks = getWindowBlocksForNetwork(networkIdStr);
            final long initialFromBlock = Math.max(0, toBlock - windowBlocks + 1);
            Long lastSynced = syncStatus.getLastBlockSynced() != null && syncStatus.getLastBlockSynced() >= initialFromBlock
                    ? syncStatus.getLastBlockSynced()
                    : null;
            long fromBlock = lastSynced != null ? lastSynced + 1 : initialFromBlock;
            if (fromBlock > toBlock) {
                syncProgressTracker.setComplete(walletAddress, networkIdStr);
                log.info("Backfill already up to date for {} on {}", walletAddress, networkIdStr);
                return;
            }
            String syncStatusId = syncStatus.getId();
            List<BackfillSegment> allSegments = ensureSegments(
                    syncStatusId, walletAddress, networkIdStr, fromBlock, toBlock, segmentConfig.parallelSegments());
            recoverStaleSegments(syncStatusId, segmentConfig.segmentStaleAfterMs());

            List<BackfillSegment> segmentsToRun = backfillSegmentRepository
                    .findBySyncStatusIdAndStatusInOrderBySegmentIndexAsc(syncStatusId, EXECUTABLE_STATUSES);
            if (segmentsToRun.isEmpty()) {
                long runningCount = backfillSegmentRepository.countBySyncStatusIdAndStatus(
                        syncStatusId, BackfillSegment.SegmentStatus.RUNNING);
                if (runningCount > 0) {
                    log.info("No executable segments for {} on {}: {} segment(s) still RUNNING",
                            walletAddress, networkIdStr, runningCount);
                }
                finalizeSyncStatusFromSegments(walletAddress, networkIdStr, syncStatusId);
                return;
            }

            int requestedWorkers = Math.max(1, segmentConfig.parallelSegmentWorkers());
            int effectiveWorkers = Math.min(requestedWorkers, segmentsToRun.size());
            try (ExecutorService segmentExecutor = Executors.newFixedThreadPool(effectiveWorkers)) {
                List<CompletableFuture<Boolean>> futures = new ArrayList<>();
                for (BackfillSegment segment : segmentsToRun) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> processOneSegment(walletAddress, networkId, adapter, segment),
                            segmentExecutor
                    ));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            finalizeSyncStatusFromSegments(walletAddress, networkIdStr, syncStatusId);
            log.info("Backfill run finished for {} on {}: total segments {}", walletAddress, networkIdStr, allSegments.size());
        } catch (Exception e) {
            String detail = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null && !e.getCause().getMessage().isBlank()) {
                detail = detail + " (" + e.getCause().getMessage() + ")";
            }
            log.warn("Backfill failed for {} on {}: {}", walletAddress, networkIdStr, detail, e);
            syncProgressTracker.setFailed(walletAddress, networkIdStr, "Backfill failed: " + detail);
        }
    }

    private long getWindowBlocksForNetwork(String networkIdStr) {
        var networkMap = ingestionNetworkProperties.getNetwork();
        var entry = networkMap != null ? networkMap.get(networkIdStr) : null;
        if (entry != null && entry.getWindowBlocks() != null && entry.getWindowBlocks() > 0) {
            return entry.getWindowBlocks();
        }
        return Math.max(1, backfillProperties.getWindowBlocks());
    }

    private List<BackfillSegment> ensureSegments(String syncStatusId, String walletAddress, String networkId,
                                                 long fromBlock, long toBlock, int requestedParallelSegments) {
        List<BackfillSegment> existing = backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(syncStatusId);
        if (!existing.isEmpty()) {
            return existing;
        }
        long totalBlocks = toBlock - fromBlock + 1;
        int plannedSegments = Math.max(1, Math.min(MAX_SEGMENTS, requestedParallelSegments));
        int segmentCount = (int) Math.max(1, Math.min(totalBlocks, plannedSegments));
        long baseSize = totalBlocks / segmentCount;
        long remainder = totalBlocks % segmentCount;
        long cursor = fromBlock;
        List<BackfillSegment> toCreate = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            long size = baseSize + (i < remainder ? 1 : 0);
            if (size <= 0) {
                continue;
            }
            BackfillSegment s = new BackfillSegment();
            s.setId(syncStatusId + ":" + i);
            s.setSyncStatusId(syncStatusId);
            s.setWalletAddress(walletAddress);
            s.setNetworkId(networkId);
            s.setSegmentIndex(i);
            s.setFromBlock(cursor);
            s.setToBlock(cursor + size - 1);
            s.setStatus(BackfillSegment.SegmentStatus.PENDING);
            s.setProgressPct(0);
            s.setRetryCount(0);
            s.setUpdatedAt(java.time.Instant.now());
            toCreate.add(s);
            cursor += size;
        }
        backfillSegmentRepository.saveAll(toCreate);
        return backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(syncStatusId);
    }

    private void recoverStaleSegments(String syncStatusId, long segmentStaleAfterMs) {
        java.time.Instant staleBefore = java.time.Instant.now().minusMillis(Math.max(1_000L, segmentStaleAfterMs));
        List<BackfillSegment> stale = backfillSegmentRepository.findBySyncStatusIdAndStatusAndUpdatedAtBefore(
                syncStatusId, BackfillSegment.SegmentStatus.RUNNING, staleBefore);
        if (stale.isEmpty()) {
            return;
        }
        java.time.Instant now = java.time.Instant.now();
        for (BackfillSegment s : stale) {
            s.setStatus(BackfillSegment.SegmentStatus.PENDING);
            s.setErrorMessage("Recovered stale RUNNING segment");
            s.setUpdatedAt(now);
        }
        backfillSegmentRepository.saveAll(stale);
    }

    private boolean processOneSegment(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                                      BackfillSegment segment) {
        try {
            long segmentToBlock = segment.getToBlock() == null ? 0L : segment.getToBlock();
            long effectiveFromBlock = resolveEffectiveFromBlock(segment);
            log.info(
                    "Starting backfill segment: wallet={}, network={}, segmentId={}, index={}, from={}, to={}",
                    walletAddress,
                    networkId.name(),
                    segment.getId(),
                    segment.getSegmentIndex(),
                    effectiveFromBlock,
                    segmentToBlock
            );
            markSegmentRunning(segment.getId());
            if (effectiveFromBlock > segmentToBlock) {
                markSegmentComplete(segment.getId());
                return true;
            }
            BackfillProgressCallback callback = (progressPct, lastBlockSynced) ->
                    updateSegmentProgress(segment.getId(), progressPct, lastBlockSynced);
            if (shouldUseRpcCheckpointing(networkId, adapter)) {
                rawFetchSegmentProcessor.processSegmentWithBlockCheckpoints(
                        walletAddress, networkId, adapter,
                        effectiveFromBlock, segmentToBlock,
                        resolveRpcCheckpointBlockSpan(networkId.name(), adapter),
                        callback
                );
            } else {
                rawFetchSegmentProcessor.processSegment(
                        walletAddress, networkId, adapter,
                        effectiveFromBlock, segmentToBlock,
                        callback
                );
            }
            markSegmentComplete(segment.getId());
            return true;
        } catch (Exception e) {
            String detail = errorDetail(e);
            log.warn(
                    "Backfill segment failed: wallet={}, network={}, segmentId={}, index={}, from={}, to={}, error={}",
                    walletAddress,
                    networkId.name(),
                    segment.getId(),
                    segment.getSegmentIndex(),
                    segment.getFromBlock(),
                    segment.getToBlock(),
                    detail,
                    e
            );
            markSegmentFailed(segment.getId(), detail);
            return false;
        }
    }

    private void markSegmentRunning(String segmentId) {
        backfillSegmentRepository.findById(segmentId).ifPresent(s -> {
            s.setStatus(BackfillSegment.SegmentStatus.RUNNING);
            s.setStartedAt(java.time.Instant.now());
            s.setErrorMessage(null);
            s.setUpdatedAt(java.time.Instant.now());
            backfillSegmentRepository.save(s);
        });
    }

    private void updateSegmentProgress(String segmentId, int progressPct, long lastBlockSynced) {
        backfillSegmentRepository.findById(segmentId).ifPresent(s -> {
            int current = s.getProgressPct() == null ? 0 : s.getProgressPct();
            int computed = computeSegmentProgressPct(s, lastBlockSynced);
            s.setProgressPct(Math.max(current, computed));
            Long currentLast = s.getLastProcessedBlock();
            if (currentLast == null || lastBlockSynced > currentLast) {
                s.setLastProcessedBlock(lastBlockSynced);
            }
            s.setUpdatedAt(java.time.Instant.now());
            backfillSegmentRepository.save(s);
        });
    }

    private void markSegmentComplete(String segmentId) {
        backfillSegmentRepository.findById(segmentId).ifPresent(s -> {
            s.setStatus(BackfillSegment.SegmentStatus.COMPLETE);
            s.setProgressPct(100);
            s.setLastProcessedBlock(s.getToBlock());
            s.setCompletedAt(java.time.Instant.now());
            s.setUpdatedAt(java.time.Instant.now());
            backfillSegmentRepository.save(s);
        });
    }

    private void markSegmentFailed(String segmentId, String error) {
        backfillSegmentRepository.findById(segmentId).ifPresent(s -> {
            s.setStatus(BackfillSegment.SegmentStatus.FAILED);
            s.setErrorMessage(error == null ? "Unknown error" : error);
            s.setRetryCount((s.getRetryCount() == null ? 0 : s.getRetryCount()) + 1);
            s.setUpdatedAt(java.time.Instant.now());
            backfillSegmentRepository.save(s);
        });
    }

    private String errorDetail(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        StringBuilder detail = new StringBuilder();
        appendThrowableDetail(detail, error);
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && cause != error && depth < 4) {
            detail.append(" <- ");
            appendThrowableDetail(detail, cause);
            cause = cause.getCause();
            depth++;
        }
        String value = detail.toString().trim();
        if (value.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private void appendThrowableDetail(StringBuilder detail, Throwable error) {
        if (error == null) {
            return;
        }
        detail.append(error.getClass().getSimpleName());
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            detail.append(": ").append(message);
        }
    }

    private void finalizeSyncStatusFromSegments(String walletAddress, String networkId, String syncStatusId) {
        List<BackfillSegment> all = backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(syncStatusId);
        if (all.isEmpty()) {
            syncProgressTracker.setComplete(walletAddress, networkId);
            return;
        }
        long total = all.size();
        long complete = all.stream().filter(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE).count();
        long failed = all.stream().filter(s -> s.getStatus() == BackfillSegment.SegmentStatus.FAILED).count();
        Long maxCompletedToBlock = all.stream()
                .filter(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                .map(BackfillSegment::getToBlock)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (complete == total) {
            Long lastSynced = maxCompletedToBlock != null ? maxCompletedToBlock : 0L;
            syncProgressTracker.setRawFetchComplete(walletAddress, networkId, lastSynced);
            syncProgressTracker.setComplete(walletAddress, networkId);
            return;
        }
        if (failed > 0) {
            syncProgressTracker.setFailed(
                    walletAddress,
                    networkId,
                    "Backfill failed: " + failed + "/" + total + " segments failed"
            );
            return;
        }
        int progressPct = averageSegmentProgressPct(all);
        syncProgressTracker.setRunning(
                walletAddress,
                networkId,
                progressPct,
                maxCompletedToBlock,
                "Raw fetch " + networkId + ": " + complete + "/" + total + " segments complete"
        );
    }

    private int averageSegmentProgressPct(List<BackfillSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        int total = segments.stream()
                .map(BackfillSegment::getProgressPct)
                .mapToInt(p -> p == null ? 0 : Math.max(0, Math.min(100, p)))
                .sum();
        return Math.max(0, Math.min(100, total / segments.size()));
    }

    private long resolveEffectiveFromBlock(BackfillSegment segment) {
        long from = segment.getFromBlock() == null ? 0L : segment.getFromBlock();
        long to = segment.getToBlock() == null ? from : segment.getToBlock();
        Long lastProcessed = segment.getLastProcessedBlock();
        if (lastProcessed == null) {
            return from;
        }
        long candidate = lastProcessed + 1;
        if (candidate < from) {
            return from;
        }
        if (candidate > to + 1) {
            return to + 1;
        }
        return candidate;
    }

    private int computeSegmentProgressPct(BackfillSegment segment, long lastBlockSynced) {
        if (segment.getFromBlock() == null || segment.getToBlock() == null) {
            return 0;
        }
        long from = segment.getFromBlock();
        long to = segment.getToBlock();
        if (to < from) {
            return 0;
        }
        long clampedLast = Math.max(from - 1, Math.min(lastBlockSynced, to));
        long processed = clampedLast < from ? 0 : (clampedLast - from + 1);
        long total = to - from + 1;
        if (total <= 0) {
            return 100;
        }
        int pct = (int) ((processed * 100) / total);
        return Math.max(0, Math.min(100, pct));
    }

    private boolean shouldUseRpcCheckpointing(NetworkId networkId, NetworkAdapter adapter) {
        if (adapter == null || !adapter.supportsBlockCheckpointing()) {
            return false;
        }
        if (networkId == NetworkId.SOLANA) {
            return false;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork() != null
                ? ingestionNetworkProperties.getNetwork().get(networkId.name())
                : null;
        return entry != null
                && entry.getSyncMethod() == IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC;
    }

    private int resolveRpcCheckpointBlockSpan(String networkIdStr, NetworkAdapter adapter) {
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork() != null
                ? ingestionNetworkProperties.getNetwork().get(networkIdStr)
                : null;
        Integer configuredBatchSize = entry != null ? entry.getBatchBlockSize() : null;
        if (configuredBatchSize != null && configuredBatchSize > 0) {
            return configuredBatchSize;
        }
        return Math.max(1, adapter.getMaxBlockBatchSize());
    }

    private SegmentExecutionConfig resolveSegmentExecutionConfig(String networkIdStr) {
        BackfillSegmentsConfiguration segmentsConfig = backfillProperties.getSegments();
        BackfillSegmentConfiguration defaults = segmentsConfig != null
                ? segmentsConfig.getDefaults()
                : null;
        BackfillSegmentConfiguration byRpc = segmentsConfig != null
                ? segmentsConfig.getByRpc()
                : null;

        long defaultStaleAfterMs = positiveOrDefault(
                defaults != null ? defaults.getSegmentStaleAfterMs() : null,
                BackfillSegmentConfiguration.DEFAULT_SEGMENT_STALE_AFTER_MS
        );
        int defaultParallelSegments = positiveOrDefault(
                defaults != null ? defaults.getParallelSegments() : null,
                BackfillSegmentConfiguration.DEFAULT_PARALLEL_SEGMENTS
        );
        int defaultParallelSegmentWorkers = positiveOrDefault(
                defaults != null ? defaults.getParallelSegmentWorkers() : null,
                BackfillSegmentConfiguration.DEFAULT_PARALLEL_SEGMENT_WORKERS
        );

        IngestionNetworkProperties.NetworkIngestionEntry networkEntry = ingestionNetworkProperties.getNetwork() != null
                ? ingestionNetworkProperties.getNetwork().get(networkIdStr)
                : null;
        boolean useRpcProfile = networkEntry != null
                && networkEntry.getSyncMethod() == IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC;

        if (!useRpcProfile) {
            return new SegmentExecutionConfig(defaultStaleAfterMs, defaultParallelSegments, defaultParallelSegmentWorkers);
        }

        long staleAfterMs = positiveOrDefault(
                byRpc != null ? byRpc.getSegmentStaleAfterMs() : null,
                defaultStaleAfterMs
        );
        int parallelSegments = positiveOrDefault(
                byRpc != null ? byRpc.getParallelSegments() : null,
                defaultParallelSegments
        );
        int parallelSegmentWorkers = positiveOrDefault(
                byRpc != null ? byRpc.getParallelSegmentWorkers() : null,
                defaultParallelSegmentWorkers
        );
        return new SegmentExecutionConfig(staleAfterMs, parallelSegments, parallelSegmentWorkers);
    }

    private static long positiveOrDefault(Long value, long fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private record SegmentExecutionConfig(long segmentStaleAfterMs, int parallelSegments, int parallelSegmentWorkers) {
    }
}
