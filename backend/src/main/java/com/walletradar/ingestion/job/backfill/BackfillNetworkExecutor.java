package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawFetchCompleteEvent;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs backfill for one (wallet, network): Phase 1 (raw fetch) only (ADR-021).
 * Classification, deferred price, and AVCO recalc are handled by separate jobs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillNetworkExecutor {

    private final RawFetchSegmentProcessor rawFetchSegmentProcessor;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final SyncProgressTracker syncProgressTracker;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SyncStatusRepository syncStatusRepository;

    public void runBackfillForNetwork(String walletAddress, NetworkId networkId,
                                      NetworkAdapter adapter, BlockHeightResolver heightResolver,
                                      BlockTimestampResolver timestampResolver) {
        String networkIdStr = networkId.name();
        try {
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, null, "Starting " + networkIdStr + "...");

            long toBlock = heightResolver.getCurrentBlock(networkId);
            long windowBlocks = getWindowBlocksForNetwork(networkIdStr);
            final long initialFromBlock = Math.max(0, toBlock - windowBlocks + 1);
            Long lastSynced = syncStatusRepository.findByWalletAddressAndNetworkId(walletAddress, networkIdStr)
                    .map(SyncStatus::getLastBlockSynced)
                    .filter(l -> l != null && l >= initialFromBlock)
                    .orElse(null);
            long fromBlock = lastSynced != null ? lastSynced + 1 : initialFromBlock;
            if (fromBlock > toBlock) {
                syncProgressTracker.setComplete(walletAddress, networkIdStr);
                log.info("Backfill already up to date for {} on {}", walletAddress, networkIdStr);
                return;
            }
            long totalBlocks = toBlock - fromBlock + 1;
            int batchSize = adapter.getMaxBlockBatchSize();

            String startMessage = lastSynced != null
                    ? "Resuming backfill for " + networkIdStr + "..."
                    : "Starting backfill for " + networkIdStr + "...";
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, fromBlock - 1, startMessage);
            log.info("Backfill started for {} on {}: blocks {}-{} ({} blocks)", walletAddress, networkIdStr, fromBlock, toBlock, totalBlocks);

            AtomicLong processedBlocks = new AtomicLong(0);

            BackfillProgressCallback progressCallback = (progressPct, lastBlock, message) ->
                    syncProgressTracker.setRunning(walletAddress, networkIdStr, progressPct, lastBlock, message);

            int parallelSegments = Math.max(1, backfillProperties.getParallelSegments());

            // Phase 1: Raw fetch only (ADR-021)
            processedBlocks.set(0);
            if (parallelSegments <= 1 || totalBlocks < 10_000) {
                rawFetchSegmentProcessor.processSegment(walletAddress, networkId, adapter,
                        fromBlock, toBlock, batchSize, processedBlocks, totalBlocks, progressCallback);
            } else {
                long segmentSize = totalBlocks / parallelSegments;
                try (ExecutorService segmentExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (int i = 0; i < parallelSegments; i++) {
                        long segStart = fromBlock + i * segmentSize;
                        long segEnd = (i == parallelSegments - 1) ? toBlock : segStart + segmentSize - 1;
                        final long fs = segStart;
                        final long fe = segEnd;
                        futures.add(CompletableFuture.runAsync(
                                () -> rawFetchSegmentProcessor.processSegment(walletAddress, networkId, adapter,
                                        fs, fe, batchSize, processedBlocks, totalBlocks, progressCallback),
                                segmentExecutor
                        ));
                    }
                    log.info("Backfill Phase 1 (raw fetch) parallel segments scheduled for {} on {}: {} segments",
                            walletAddress, networkIdStr, parallelSegments);
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    log.info("Backfill Phase 1 (raw fetch) complete for {} on {}", walletAddress, networkIdStr);
                }
            }

            syncProgressTracker.setRawFetchComplete(walletAddress, networkIdStr, toBlock);
            applicationEventPublisher.publishEvent(new RawFetchCompleteEvent(walletAddress, networkIdStr, toBlock));
            syncProgressTracker.setComplete(walletAddress, networkIdStr);
            log.info("Backfill complete for {} on {}", walletAddress, networkIdStr);
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
        var entry = ingestionNetworkProperties.getNetwork().get(networkIdStr);
        if (entry != null && entry.getWindowBlocks() != null && entry.getWindowBlocks() > 0) {
            return entry.getWindowBlocks();
        }
        return Math.max(1, backfillProperties.getWindowBlocks());
    }
}
