package com.walletradar.ingestion.job;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.normalizer.EconomicEventNormalizer;
import com.walletradar.ingestion.store.IdempotentEventStore;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes one block-range segment: fetch → classify → normalize → PRICE_PENDING → upsert.
 * Reports progress via {@link BackfillProgressCallback}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackfillSegmentProcessor {

    private final TxClassifierDispatcher txClassifierDispatcher;
    private final EconomicEventNormalizer economicEventNormalizer;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;
    private final IdempotentEventStore idempotentEventStore;
    private final InlineSwapPriceEnricher inlineSwapPriceEnricher;

    public void processSegment(String walletAddress, NetworkId networkId,
                               NetworkAdapter adapter, EstimatingBlockTimestampResolver estimator,
                               long segFromBlock, long segToBlock,
                               Map<LocalDate, BigDecimal> nativePriceCache, String nativeContract,
                               Set<String> sessionWallets, int batchSize,
                               AtomicLong processedBlocks, long totalBlocks,
                               BackfillProgressCallback progressCallback) {
        long start = segFromBlock;
        while (start <= segToBlock) {
            long end = Math.min(start + batchSize - 1, segToBlock);
            List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, start, end);
            for (RawTransaction tx : batch) {
                Long blockNum = getBlockNumberFromRaw(tx);
                if (blockNum == null) continue;
                Instant blockTs = estimator.estimate(networkId, blockNum);
                BigDecimal nativePriceUsd = resolveNativePrice(nativeContract, networkId, blockTs, nativePriceCache);
                List<RawClassifiedEvent> rawEvents = txClassifierDispatcher.classify(tx, walletAddress, sessionWallets);
                List<EconomicEvent> events = economicEventNormalizer.normalizeAll(rawEvents,
                        tx.getTxHash(), networkId, blockTs, nativePriceUsd);
                inlineSwapPriceEnricher.enrich(events);
                for (EconomicEvent event : events) {
                    if (event.getPriceUsd() == null) {
                        event.setFlagCode(FlagCode.PRICE_PENDING);
                        event.setFlagResolved(false);
                    }
                    idempotentEventStore.upsert(event);
                }
            }
            long blocksInBatch = end - start + 1;
            long totalProcessed = processedBlocks.addAndGet(blocksInBatch);
            int progressPct = totalBlocks <= 0 ? 100 : (int) Math.min(100, (totalProcessed * 100) / totalBlocks);
            progressCallback.reportProgress(progressPct, end,
                    "Syncing " + networkId.name() + ": " + progressPct + "% complete");
            start = end + 1;
        }
    }

    static Long getBlockNumberFromRaw(RawTransaction tx) {
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

    /**
     * Resolve the native token's USD price for gas cost calculation.
     * Cached per date (CoinGecko historical API granularity is daily).
     */
    private BigDecimal resolveNativePrice(String nativeContract, NetworkId networkId,
                                          Instant blockTs, Map<LocalDate, BigDecimal> cache) {
        if (nativeContract == null || blockTs == null) return BigDecimal.ZERO;
        LocalDate date = blockTs.atOffset(ZoneOffset.UTC).toLocalDate();
        return cache.computeIfAbsent(date, d -> {
            HistoricalPriceRequest req = new HistoricalPriceRequest();
            req.setAssetContract(nativeContract);
            req.setNetworkId(networkId);
            req.setBlockTimestamp(blockTs);
            PriceResolutionResult result = historicalPriceResolverChain.resolve(req);
            if (!result.isUnknown() && result.getPriceUsd().isPresent()) {
                log.debug("Native price for {} on {}: {} USD", networkId, date, result.getPriceUsd().get());
                return result.getPriceUsd().get();
            }
            log.warn("Native price UNKNOWN for {} on {}; gas costs will be zero", networkId, date);
            return BigDecimal.ZERO;
        });
    }
}
