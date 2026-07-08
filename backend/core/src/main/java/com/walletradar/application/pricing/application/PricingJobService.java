package com.walletradar.application.pricing.application;

import com.walletradar.platform.common.config.AsyncConfig;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Batch processor for the pricing stage.
 */
@Service
@Slf4j
public class PricingJobService {

    private final PendingPricingQueryService pendingPricingQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final PriceResolutionService priceResolutionService;
    private final PricingResultMapper pricingResultMapper;
    private final BatchPriceQuoteResolver batchPriceQuoteResolver;
    private final PricingProperties pricingProperties;
    private final Executor pricingExecutor;

    public PricingJobService(
            PendingPricingQueryService pendingPricingQueryService,
            NormalizedTransactionRepository normalizedTransactionRepository,
            PriceResolutionService priceResolutionService,
            PricingResultMapper pricingResultMapper,
            BatchPriceQuoteResolver batchPriceQuoteResolver,
            PricingProperties pricingProperties,
            @Qualifier(AsyncConfig.PRICING_EXECUTOR) Executor pricingExecutor
    ) {
        this.pendingPricingQueryService = pendingPricingQueryService;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.priceResolutionService = priceResolutionService;
        this.pricingResultMapper = pricingResultMapper;
        this.batchPriceQuoteResolver = batchPriceQuoteResolver;
        this.pricingProperties = pricingProperties;
        this.pricingExecutor = pricingExecutor;
    }

    public int processNextBatch() {
        return processNextBatch(() -> {
        });
    }

    public int processNextBatch(Runnable progressHeartbeat) {
        List<NormalizedTransaction> batch = pendingPricingQueryService.loadNextBatch(
                pricingProperties.getBatchSize(),
                pricingProperties.getRetryDelaySeconds()
        );
        if (batch.isEmpty()) {
            return 0;
        }

        BatchPriceQuoteResolver.BatchQuotePlan batchQuotePlan = batchPriceQuoteResolver.prepare(batch);
        List<PricingOutcome> outcomes = resolveBatch(batch, progressHeartbeat, batchQuotePlan);
        List<NormalizedTransaction> persisted = outcomes.stream()
                .sorted(Comparator.comparingInt(PricingOutcome::index))
                .map(PricingOutcome::transaction)
                .toList();
        batchPriceQuoteResolver.persistFetchedQuotes(batchQuotePlan);
        normalizedTransactionRepository.saveAll(persisted);
        return (int) outcomes.stream().filter(PricingOutcome::successful).count();
    }

    private List<PricingOutcome> resolveBatch(
            List<NormalizedTransaction> batch,
            Runnable progressHeartbeat,
            BatchPriceQuoteResolver.BatchQuotePlan batchQuotePlan
    ) {
        int lanes = Math.max(1, Math.min(pricingProperties.getParallelLanes(), batch.size()));
        if (lanes == 1 || batch.size() == 1) {
            return resolvePartition(index(batch, 0), progressHeartbeat, batchQuotePlan);
        }

        List<List<IndexedTransaction>> partitions = partition(index(batch, 0), lanes);
        List<CompletableFuture<List<PricingOutcome>>> futures = partitions.stream()
                .map(partition -> CompletableFuture.supplyAsync(
                        () -> resolvePartition(partition, progressHeartbeat, batchQuotePlan),
                        pricingExecutor
                ))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();
    }

    private List<PricingOutcome> resolvePartition(
            List<IndexedTransaction> partition,
            Runnable progressHeartbeat,
            BatchPriceQuoteResolver.BatchQuotePlan batchQuotePlan
    ) {
        List<PricingOutcome> outcomes = new ArrayList<>(partition.size());
        for (IndexedTransaction indexedTransaction : partition) {
            outcomes.add(resolveOne(indexedTransaction, batchQuotePlan));
            progressHeartbeat.run();
        }
        return outcomes;
    }

    private PricingOutcome resolveOne(
            IndexedTransaction indexedTransaction,
            BatchPriceQuoteResolver.BatchQuotePlan batchQuotePlan
    ) {
        NormalizedTransaction transaction = indexedTransaction.transaction();
        Instant now = Instant.now();
        try {
            NormalizedTransaction priced = priceResolutionService.resolve(
                    transaction,
                    now,
                    request -> batchPriceQuoteResolver.resolve(request, batchQuotePlan)
            );
            return new PricingOutcome(indexedTransaction.index(), priced, true);
        } catch (RuntimeException error) {
            log.warn(
                    "Pricing failed for normalizedTxId={}: {}",
                    transaction.getId(),
                    error.getMessage(),
                    error
            );
            NormalizedTransaction failed = pricingResultMapper.copy(transaction);
            pricingResultMapper.markFailedAttempt(failed, now);
            return new PricingOutcome(indexedTransaction.index(), failed, false);
        }
    }

    private List<IndexedTransaction> index(List<NormalizedTransaction> batch, int startIndex) {
        List<IndexedTransaction> indexed = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            indexed.add(new IndexedTransaction(startIndex + i, batch.get(i)));
        }
        return indexed;
    }

    private List<List<IndexedTransaction>> partition(List<IndexedTransaction> indexedBatch, int lanes) {
        List<List<IndexedTransaction>> partitions = new ArrayList<>(lanes);
        int partitionSize = (int) Math.ceil((double) indexedBatch.size() / lanes);
        for (int start = 0; start < indexedBatch.size(); start += partitionSize) {
            partitions.add(indexedBatch.subList(start, Math.min(indexedBatch.size(), start + partitionSize)));
        }
        return partitions;
    }

    private record IndexedTransaction(int index, NormalizedTransaction transaction) {
    }

    private record PricingOutcome(int index, NormalizedTransaction transaction, boolean successful) {
    }
}
