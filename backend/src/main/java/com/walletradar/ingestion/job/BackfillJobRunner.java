package com.walletradar.ingestion.job;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.domain.RecalculateWalletRequestEvent;
import com.walletradar.domain.WalletAddedEvent;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.classifier.InternalTransferReclassifier;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.normalizer.EconomicEventNormalizer;
import com.walletradar.ingestion.store.IdempotentEventStore;
import com.walletradar.pricing.HistoricalPriceRequest;
import org.springframework.context.ApplicationEventPublisher;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.walletradar.domain.SyncStatus.SyncStatusValue;


/**
 * Runs backfill on WalletAddedEvent: per-network parallel fetch → classify → normalize → price → store → AVCO recalc (T-009).
 * Uses a single work queue and N worker loops (ADR-014): as soon as a thread frees up, it takes the next job (PENDING/FAILED).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillJobRunner {

    private record BackfillWorkItem(String walletAddress, NetworkId networkId) {}

    private final BlockingQueue<BackfillWorkItem> backfillQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean workersStarted = new AtomicBoolean(false);

    private final List<NetworkAdapter> networkAdapters;
    private final List<BlockHeightResolver> blockHeightResolvers;
    private final List<BlockTimestampResolver> blockTimestampResolvers;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final TxClassifierDispatcher txClassifierDispatcher;
    private final EconomicEventNormalizer economicEventNormalizer;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;
    private final IdempotentEventStore idempotentEventStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SyncProgressTracker syncProgressTracker;
    private final InternalTransferReclassifier internalTransferReclassifier;
    private final SyncStatusRepository syncStatusRepository;
    @Qualifier("backfill-coordinator-executor")
    private final Executor backfillCoordinatorExecutor;
    @Qualifier("backfill-executor")
    private final Executor backfillExecutor;

    @EventListener
    public void onWalletAdded(WalletAddedEvent event) {
        backfillCoordinatorExecutor.execute(() -> enqueueWork(event.walletAddress(), event.networks()));
    }

    /**
     * On startup: start N worker loops (once), then enqueue all PENDING/RUNNING/FAILED so free threads take the next job.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startWorkersIfNeeded();
        List<SyncStatus> incomplete = syncStatusRepository.findByStatusIn(
                Set.of(SyncStatusValue.PENDING, SyncStatusValue.RUNNING, SyncStatusValue.FAILED));
        if (incomplete.isEmpty()) return;
        int enqueued = 0;
        for (SyncStatus s : incomplete) {
            if (s.getWalletAddress() == null || s.getNetworkId() == null) continue;
            try {
                BackfillWorkItem item = new BackfillWorkItem(s.getWalletAddress(), NetworkId.valueOf(s.getNetworkId()));
                if (enqueueItemIfSupported(item)) enqueued++;
            } catch (IllegalArgumentException ignored) { /* unknown network */ }
        }
        if (enqueued > 0) {
            log.info("Resuming backfill: {} work item(s) enqueued (PENDING/RUNNING/FAILED), workers will take next available", enqueued);
        }
    }

    void runBackfill(String walletAddress, List<NetworkId> networks) {
        enqueueWork(walletAddress, networks);
    }

    /** Enqueues (wallet, network) items; skips unsupported networks and marks them complete. */
    void enqueueWork(String walletAddress, List<NetworkId> networks) {
        for (NetworkId n : networks) {
            enqueueItemIfSupported(new BackfillWorkItem(walletAddress, n));
        }
    }

    /** Returns true if item was enqueued, false if skipped (unsupported). */
    private boolean enqueueItemIfSupported(BackfillWorkItem item) {
        NetworkAdapter adapter = findAdapter(item.networkId());
        BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
        BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
        if (adapter == null || heightResolver == null || timestampResolver == null) {
            log.info("Skipping backfill for {} (adapter/block resolver not available)", item.networkId());
            syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
            return false;
        }
        backfillQueue.offer(item);
        return true;
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
                NetworkAdapter adapter = findAdapter(item.networkId());
                BlockHeightResolver heightResolver = findBlockHeightResolver(item.networkId());
                BlockTimestampResolver timestampResolver = findBlockTimestampResolver(item.networkId());
                if (adapter == null || heightResolver == null || timestampResolver == null) {
                    syncProgressTracker.setComplete(item.walletAddress(), item.networkId().name());
                    if (backfillQueue.isEmpty()) {
                        backfillCoordinatorExecutor.execute(this::runReclassifyAndRecalc);
                    }
                    continue;
                }
                runBackfillForNetwork(item.walletAddress(), item.networkId(), adapter, heightResolver, timestampResolver);
                if (backfillQueue.isEmpty()) {
                    backfillCoordinatorExecutor.execute(this::runReclassifyAndRecalc);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Backfill worker interrupted");
                break;
            }
        }
    }

    private void runReclassifyAndRecalc() {
        Set<String> sessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .collect(Collectors.toSet());
        List<EconomicEvent> reclassified = internalTransferReclassifier.reclassify(sessionWallets);
        Set<String> affectedWallets = reclassified.stream().map(EconomicEvent::getWalletAddress).collect(Collectors.toSet());
        for (String w : affectedWallets) {
            applicationEventPublisher.publishEvent(new RecalculateWalletRequestEvent(w));
        }
    }

    private void runBackfillForNetwork(String walletAddress, NetworkId networkId,
                                       NetworkAdapter adapter, BlockHeightResolver heightResolver,
                                       BlockTimestampResolver timestampResolver) {
        Set<String> sessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .collect(Collectors.toSet());
        String networkIdStr = networkId.name();
        try {
            // Mark RUNNING immediately so this network shows as in progress (not just PENDING) while in executor.
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, null, "Starting " + networkIdStr + "...");

            long toBlock = heightResolver.getCurrentBlock(networkId);
            long windowBlocks = getWindowBlocksForNetwork(networkIdStr);
            final long initialFromBlock = Math.max(0, toBlock - windowBlocks + 1);
            // Resume from last synced block if present (e.g. after restart)
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

            String startMessage = lastSynced != null ? "Resuming backfill for " + networkIdStr + "..." : "Starting backfill for " + networkIdStr + "...";
            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, fromBlock - 1, startMessage);
            log.info("Backfill started for {} on {}: blocks {}-{} ({} blocks)", walletAddress, networkIdStr, fromBlock, toBlock, totalBlocks);

            long processedBlocks = 0;
            long start = fromBlock;
            Map<Long, Instant> blockTimestampCache = new HashMap<>();

            while (start <= toBlock) {
                long end = Math.min(start + batchSize - 1, toBlock);
                List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, start, end);
                for (RawTransaction tx : batch) {
                    Long blockNum = getBlockNumberFromRaw(tx);
                    if (blockNum == null) continue;
                    Instant blockTs = blockTimestampCache.computeIfAbsent(blockNum, n -> timestampResolver.getBlockTimestamp(networkId, n));
                    List<RawClassifiedEvent> rawEvents = txClassifierDispatcher.classify(tx, walletAddress, sessionWallets);
                    List<EconomicEvent> events = economicEventNormalizer.normalizeAll(rawEvents,
                            tx.getTxHash(), networkId, blockTs, BigDecimal.ZERO);
                    for (EconomicEvent event : events) {
                        resolvePriceAndFlag(event, networkId);
                        idempotentEventStore.upsert(event);
                    }
                }
                processedBlocks += (end - start + 1);
                int progressPct = totalBlocks <= 0 ? 100 : (int) Math.min(100, (processedBlocks * 100) / totalBlocks);
                syncProgressTracker.setRunning(walletAddress, networkIdStr, progressPct, end, "Syncing " + networkIdStr + ": " + progressPct + "% complete");
                start = end + 1;
            }

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

    private Long getBlockNumberFromRaw(RawTransaction tx) {
        if (tx.getRawData() == null || !tx.getRawData().containsKey("blockNumber")) return null;
        Object bn = tx.getRawData().get("blockNumber");
        if (bn == null) return null;
        String hex = bn.toString();
        if (!hex.startsWith("0x")) return null;
        try {
            return Long.parseLong(hex.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void resolvePriceAndFlag(EconomicEvent event, NetworkId networkId) {
        HistoricalPriceRequest request = new HistoricalPriceRequest();
        request.setAssetContract(event.getAssetContract());
        request.setNetworkId(networkId);
        request.setBlockTimestamp(event.getBlockTimestamp());
        PriceResolutionResult result = historicalPriceResolverChain.resolve(request);
        if (!result.isUnknown() && result.getPriceUsd().isPresent()) {
            event.setPriceUsd(result.getPriceUsd().get());
            event.setPriceSource(result.getPriceSource());
            event.setFlagResolved(true);
        } else {
            event.setFlagCode(FlagCode.PRICE_UNKNOWN);
            event.setFlagResolved(false);
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

    /** Per-network backfill window (blocks). L2s need larger window to cover ~1 year (e.g. Arbitrum ~4 blocks/s). */
    private long getWindowBlocksForNetwork(String networkIdStr) {
        var entry = ingestionNetworkProperties.getNetwork().get(networkIdStr);
        if (entry != null && entry.getWindowBlocks() != null && entry.getWindowBlocks() > 0) {
            return entry.getWindowBlocks();
        }
        return Math.max(1, backfillProperties.getWindowBlocks());
    }
}
