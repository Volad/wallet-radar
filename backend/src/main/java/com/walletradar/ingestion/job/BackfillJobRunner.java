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
import com.walletradar.ingestion.normalizer.EconomicEventNormalizer;
import com.walletradar.ingestion.store.IdempotentEventStore;
import com.walletradar.pricing.HistoricalPriceRequest;
import org.springframework.context.ApplicationEventPublisher;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;


/**
 * Runs backfill on WalletAddedEvent: per-network parallel fetch → classify → normalize → price → store → AVCO recalc (T-009).
 */
@Component
@Slf4j
public class BackfillJobRunner {

    private final List<NetworkAdapter> networkAdapters;
    private final List<BlockHeightResolver> blockHeightResolvers;
    private final List<BlockTimestampResolver> blockTimestampResolvers;
    private final BackfillProperties backfillProperties;
    private final TxClassifierDispatcher txClassifierDispatcher;
    private final EconomicEventNormalizer economicEventNormalizer;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;
    private final IdempotentEventStore idempotentEventStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SyncProgressTracker syncProgressTracker;
    private final InternalTransferReclassifier internalTransferReclassifier;
    private final SyncStatusRepository syncStatusRepository;
    private final Executor backfillExecutor;

    public BackfillJobRunner(List<NetworkAdapter> networkAdapters, List<BlockHeightResolver> blockHeightResolvers,
                             List<BlockTimestampResolver> blockTimestampResolvers, BackfillProperties backfillProperties,
                             TxClassifierDispatcher txClassifierDispatcher, EconomicEventNormalizer economicEventNormalizer,
                             HistoricalPriceResolverChain historicalPriceResolverChain, IdempotentEventStore idempotentEventStore,
                             ApplicationEventPublisher applicationEventPublisher, SyncProgressTracker syncProgressTracker,
                             InternalTransferReclassifier internalTransferReclassifier, SyncStatusRepository syncStatusRepository,
                             @Qualifier("backfill-executor") Executor backfillExecutor) {
        this.networkAdapters = networkAdapters;
        this.blockHeightResolvers = blockHeightResolvers;
        this.blockTimestampResolvers = blockTimestampResolvers;
        this.backfillProperties = backfillProperties;
        this.txClassifierDispatcher = txClassifierDispatcher;
        this.economicEventNormalizer = economicEventNormalizer;
        this.historicalPriceResolverChain = historicalPriceResolverChain;
        this.idempotentEventStore = idempotentEventStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.syncProgressTracker = syncProgressTracker;
        this.internalTransferReclassifier = internalTransferReclassifier;
        this.syncStatusRepository = syncStatusRepository;
        this.backfillExecutor = backfillExecutor;
    }

    @EventListener
    public void onWalletAdded(WalletAddedEvent event) {
        backfillExecutor.execute(() -> runBackfill(event.walletAddress(), event.networks()));
    }

    void runBackfill(String walletAddress, List<NetworkId> networks) {
        Set<String> initialSessionWallets = syncStatusRepository.findAll().stream()
                .map(SyncStatus::getWalletAddress)
                .collect(Collectors.toSet());
        initialSessionWallets.add(walletAddress);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (NetworkId networkId : networks) {
            NetworkAdapter adapter = findAdapter(networkId);
            BlockHeightResolver heightResolver = findBlockHeightResolver(networkId);
            BlockTimestampResolver timestampResolver = findBlockTimestampResolver(networkId);
            if (adapter == null || heightResolver == null || timestampResolver == null) {
                log.info("Skipping backfill for {} (adapter/block resolver not available)", networkId);
                syncProgressTracker.setComplete(walletAddress, networkId.name());
                continue;
            }
            CompletableFuture<Void> f = CompletableFuture.runAsync(() ->
                    runBackfillForNetwork(walletAddress, networkId, adapter, heightResolver, timestampResolver, initialSessionWallets), backfillExecutor);
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

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
                                       BlockTimestampResolver timestampResolver, Set<String> sessionWallets) {
        String networkIdStr = networkId.name();
        try {
            long toBlock = heightResolver.getCurrentBlock(networkId);
            long windowBlocks = Math.max(1, backfillProperties.getWindowBlocks());
            long fromBlock = Math.max(0, toBlock - windowBlocks + 1);
            long totalBlocks = toBlock - fromBlock + 1;
            int batchSize = adapter.getMaxBlockBatchSize();

            syncProgressTracker.setRunning(walletAddress, networkIdStr, 0, null, "Starting backfill for " + networkIdStr + "...");

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
            log.warn("Backfill failed for {} on {}: {}", walletAddress, networkIdStr, e.getMessage());
            syncProgressTracker.setFailed(walletAddress, networkIdStr, "Backfill failed: " + e.getMessage());
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
}
