package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.job.DeferredPriceResolutionJob;
import com.walletradar.ingestion.job.SyncProgressTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Runs backfill for one (wallet, network): block range resolution, estimator calibration,
 * sequential vs parallel segments, deferred price resolution, recalc event, status transitions.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillNetworkExecutor {

    private static final Map<NetworkId, String> NATIVE_TOKEN_ADDRESS = Map.of(
            NetworkId.ETHEREUM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.ARBITRUM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.OPTIMISM,  "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.BASE,      "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            NetworkId.MANTLE,    "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8",
            NetworkId.POLYGON,   "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270",
            NetworkId.BSC,       "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
            NetworkId.AVALANCHE, "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7",
            NetworkId.SOLANA,    "So11111111111111111111111111111111111111112"
    );

    private final RawFetchSegmentProcessor rawFetchSegmentProcessor;
    private final ClassificationProcessor classificationProcessor;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final SyncProgressTracker syncProgressTracker;
    private final DeferredPriceResolutionJob deferredPriceResolutionJob;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SyncStatusRepository syncStatusRepository;

    public void runBackfillForNetwork(String walletAddress, NetworkId networkId,
                                      NetworkAdapter adapter, BlockHeightResolver heightResolver,
                                      BlockTimestampResolver timestampResolver) {
        Set<String> sessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .collect(Collectors.toSet());
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

            EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
            double fallbackAvgBlockTime = getFallbackAvgBlockTime(networkIdStr);
            estimator.calibrate(networkId, fromBlock, toBlock, timestampResolver, fallbackAvgBlockTime);
            log.info("Block timestamp estimator calibrated for {} (fallback={} s)", networkIdStr, fallbackAvgBlockTime);

            String startMessage = lastSynced != null
                    ? "Resuming backfill for " + networkIdStr + "..."
                    : "Starting backfill for " + networkIdStr + "...";
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, fromBlock - 1, startMessage);
            log.info("Backfill started for {} on {}: blocks {}-{} ({} blocks)", walletAddress, networkIdStr, fromBlock, toBlock, totalBlocks);

            Map<LocalDate, BigDecimal> nativePriceCache = new ConcurrentHashMap<>();
            String nativeContract = NATIVE_TOKEN_ADDRESS.get(networkId);
            AtomicLong processedBlocks = new AtomicLong(0);

            BackfillProgressCallback progressCallback = (progressPct, lastBlock, message) ->
                    syncProgressTracker.setRunning(walletAddress, networkIdStr, progressPct, lastBlock, message);

            int parallelSegments = Math.max(1, backfillProperties.getParallelSegments());

            // Phase 1: Raw fetch (ADR-020)
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

            // Phase 2: Classification (ADR-020)
            processedBlocks.set(0);
            if (parallelSegments <= 1 || totalBlocks < 10_000) {
                classificationProcessor.processSegment(walletAddress, networkId,
                        fromBlock, toBlock, estimator, nativePriceCache, nativeContract,
                        sessionWallets, processedBlocks, totalBlocks, progressCallback);
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
                                () -> classificationProcessor.processSegment(walletAddress, networkId,
                                        fs, fe, estimator, nativePriceCache, nativeContract,
                                        sessionWallets, processedBlocks, totalBlocks, progressCallback),
                                segmentExecutor
                        ));
                    }
                    log.info("Backfill Phase 2 (classification) parallel segments scheduled for {} on {}: {} segments",
                            walletAddress, networkIdStr, parallelSegments);
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    log.info("Backfill Phase 2 (classification) complete for {} on {}", walletAddress, networkIdStr);
                }
            }
            syncProgressTracker.setClassificationComplete(walletAddress, networkIdStr);

            // Phase 3: Deferred price resolution + AVCO recalc
            deferredPriceResolutionJob.resolveForWallet(walletAddress);
            applicationEventPublisher.publishEvent(new RecalculateWalletRequestEvent(walletAddress));
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

    private double getFallbackAvgBlockTime(String networkIdStr) {
        var entry = ingestionNetworkProperties.getNetwork().get(networkIdStr);
        if (entry != null && entry.getAvgBlockTimeSeconds() != null && entry.getAvgBlockTimeSeconds() > 0) {
            return entry.getAvgBlockTimeSeconds();
        }
        return 12.0;
    }
}
