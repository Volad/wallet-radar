package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.job.InlineSwapPriceEnricher;
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
 * Phase 2 (ADR-020): Read raw_transactions → classify → normalize → inline swap price → PRICE_PENDING → upsert.
 * No RPC calls; classification retry does not re-fetch.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClassificationProcessor {

    private final RawTransactionRepository rawTransactionRepository;
    private final TxClassifierDispatcher txClassifierDispatcher;
    private final EconomicEventNormalizer economicEventNormalizer;
    private final InlineSwapPriceEnricher inlineSwapPriceEnricher;
    private final IdempotentEventStore idempotentEventStore;
    private final HistoricalPriceResolverChain historicalPriceResolverChain;

    /**
     * Process one block-range segment: read raw from DB, classify, normalize, upsert events.
     *
     * @param walletAddress     wallet
     * @param networkId         network
     * @param segFromBlock      segment start (block or slot)
     * @param segToBlock        segment end
     * @param estimator         block timestamp estimator (EVM); null for Solana (uses blockTime from raw)
     * @param nativePriceCache  cache for native token USD price
     * @param nativeContract   native token contract for gas cost
     * @param sessionWallets   all session wallets (internal transfer detection)
     * @param processedBlocks  shared progress counter
     * @param totalBlocks      total blocks
     * @param progressCallback progress reporter
     */
    public void processSegment(String walletAddress, NetworkId networkId,
                               long segFromBlock, long segToBlock,
                               EstimatingBlockTimestampResolver estimator,
                               Map<LocalDate, BigDecimal> nativePriceCache, String nativeContract,
                               Set<String> sessionWallets,
                               AtomicLong processedBlocks, long totalBlocks,
                               BackfillProgressCallback progressCallback) {
        List<RawTransaction> rawList = networkId == NetworkId.SOLANA
                ? rawTransactionRepository.findByWalletAddressAndNetworkIdAndSlotBetweenOrderBySlotAsc(
                walletAddress, networkId.name(), segFromBlock, segToBlock)
                : rawTransactionRepository.findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(
                walletAddress, networkId.name(), segFromBlock, segToBlock);

        for (RawTransaction tx : rawList) {
            try {
                Instant blockTs = resolveBlockTimestamp(tx, networkId, estimator);
                if (blockTs == null) continue;
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
            } catch (Exception e) {
                log.error("Classification failed for tx {} on {} for wallet {}: {}",
                        tx.getTxHash(), networkId, walletAddress, e.getMessage(), e);
            }
        }
        long blocksInSegment = segToBlock - segFromBlock + 1;
        long totalProcessed = processedBlocks.addAndGet(blocksInSegment);
        int progressPct = totalBlocks <= 0 ? 100 : (int) Math.min(100, (totalProcessed * 100) / totalBlocks);
        progressCallback.reportProgress(progressPct, segToBlock,
                "Classifying " + networkId.name() + ": " + progressPct + "% complete");
    }


    private Instant resolveBlockTimestamp(RawTransaction tx, NetworkId networkId,
                                          EstimatingBlockTimestampResolver estimator) {
        if (networkId == NetworkId.SOLANA) {
            Object blockTime = tx.getRawData() != null ? tx.getRawData().get("blockTime") : null;
            if (blockTime instanceof Number n) {
                return Instant.ofEpochSecond(n.longValue());
            }
            return null;
        }
        Long blockNum = tx.getBlockNumber() != null ? tx.getBlockNumber()
                : getBlockNumberFromRaw(tx);
        if (blockNum == null || estimator == null) return null;
        return estimator.estimate(networkId, blockNum);
    }

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
                return result.getPriceUsd().get();
            }
            return BigDecimal.ZERO;
        });
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
}
