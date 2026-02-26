package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.pipeline.enrichment.InlineSwapPriceEnricher;
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

/**
 * Phase 2 (ADR-020, ADR-021): Read raw_transactions → classify → normalize → inline swap price → PRICE_PENDING → upsert.
 * No RPC calls; classification retry does not re-fetch.
 * Used by {@link com.walletradar.ingestion.job.classification.RawTransactionClassifierJob}.
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
     * Process a batch of raw transactions directly (ADR-021). Used by RawTransactionClassifierJob.
     * Sets classificationStatus=COMPLETE on success, FAILED on exception.
     */
    public void processBatch(List<RawTransaction> rawList, String walletAddress, NetworkId networkId,
                            EstimatingBlockTimestampResolver estimator,
                            Map<LocalDate, BigDecimal> nativePriceCache, String nativeContract,
                            Set<String> sessionWallets) {
        for (RawTransaction tx : rawList) {
            try {
                Instant blockTs = resolveBlockTimestamp(tx, networkId, estimator);
                if (blockTs == null) {
                    tx.setClassificationStatus(ClassificationStatus.FAILED);
                    rawTransactionRepository.save(tx);
                    continue;
                }
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
                tx.setClassificationStatus(ClassificationStatus.COMPLETE);
                rawTransactionRepository.save(tx);
            } catch (Exception e) {
                log.error("Classification failed for tx {} on {} for wallet {}: {}",
                        tx.getTxHash(), networkId, walletAddress, e.getMessage(), e);
                tx.setClassificationStatus(ClassificationStatus.FAILED);
                rawTransactionRepository.save(tx);
            }
        }
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

    public static Long getBlockNumberFromRaw(RawTransaction tx) {
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
