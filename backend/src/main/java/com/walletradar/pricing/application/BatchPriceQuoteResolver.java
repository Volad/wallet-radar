package com.walletradar.pricing.application;

import com.walletradar.config.AsyncConfig;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.domain.PriceResolutionContext;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Batch-scoped quote resolver that preloads warm cache entries and stages newly fetched quotes for bulk persistence.
 */
@Service
@Slf4j
public class BatchPriceQuoteResolver {

    private final HistoricalPriceCacheService historicalPriceCacheService;
    private final PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;
    private final PricingProperties pricingProperties;
    private final Executor pricingExecutor;

    public BatchPriceQuoteResolver(
            HistoricalPriceCacheService historicalPriceCacheService,
            PriceExternalSourceOrchestrator priceExternalSourceOrchestrator,
            PricingProperties pricingProperties,
            @Qualifier(AsyncConfig.PRICING_EXECUTOR) Executor pricingExecutor
    ) {
        this.historicalPriceCacheService = Objects.requireNonNull(
                historicalPriceCacheService,
                "historicalPriceCacheService"
        );
        this.priceExternalSourceOrchestrator = Objects.requireNonNull(
                priceExternalSourceOrchestrator,
                "priceExternalSourceOrchestrator"
        );
        this.pricingProperties = Objects.requireNonNull(pricingProperties, "pricingProperties");
        this.pricingExecutor = Objects.requireNonNull(pricingExecutor, "pricingExecutor");
    }

    public BatchQuotePlan prepare(List<NormalizedTransaction> batch) {
        Map<BatchQuoteKey, PriceRequest> requestsByKey = extractUniqueRequests(batch);
        if (requestsByKey.isEmpty()) {
            return BatchQuotePlan.empty();
        }

        Map<BatchQuoteKey, List<PriceSource>> prioritizedSourcesByKey = new LinkedHashMap<>();
        Set<String> documentIds = new LinkedHashSet<>();
        for (Map.Entry<BatchQuoteKey, PriceRequest> entry : requestsByKey.entrySet()) {
            List<PriceSource> prioritizedSources = priceExternalSourceOrchestrator.prioritizedSources(entry.getValue());
            if (prioritizedSources.isEmpty()) {
                continue;
            }
            prioritizedSourcesByKey.put(entry.getKey(), prioritizedSources);
            for (PriceSource source : prioritizedSources) {
                documentIds.add(historicalPriceCacheService.documentId(entry.getValue(), source));
            }
        }

        Map<String, HistoricalPriceDocument> cachedDocuments = historicalPriceCacheService.findDocumentsByIds(documentIds);
        ConcurrentMap<BatchQuoteKey, QuoteResolution> quoteCache = new ConcurrentHashMap<>();
        for (Map.Entry<BatchQuoteKey, PriceRequest> entry : requestsByKey.entrySet()) {
            List<PriceSource> prioritizedSources = prioritizedSourcesByKey.getOrDefault(entry.getKey(), List.of());
            Optional<PriceQuote> cachedQuote = prioritizedSources.stream()
                    .map(source -> cachedDocuments.get(historicalPriceCacheService.documentId(entry.getValue(), source)))
                    .filter(Objects::nonNull)
                    .map(historicalPriceCacheService::toQuote)
                    .findFirst();
            cachedQuote.ifPresent(priceQuote -> quoteCache.put(
                    entry.getKey(),
                    new QuoteResolution(Optional.of(priceQuote))
            ));
        }

        ConcurrentMap<String, HistoricalPriceDocument> stagedDocuments = new ConcurrentHashMap<>();
        prefetchMissingQuotes(requestsByKey, quoteCache, stagedDocuments);

        long cacheHits = quoteCache.values().stream()
                .filter(resolution -> resolution.quote().isPresent())
                .count();
        log.info(
                "Pricing batch quote prefetch: batchSize={}, uniqueRequests={}, cacheOrResolvedHits={}, unresolved={}, stagedQuotes={}",
                batch == null ? 0 : batch.size(),
                requestsByKey.size(),
                cacheHits,
                Math.max(0, requestsByKey.size() - cacheHits),
                stagedDocuments.size()
        );

        return new BatchQuotePlan(quoteCache, stagedDocuments);
    }

    public Optional<PriceQuote> resolve(PriceRequest request, BatchQuotePlan plan) {
        if (plan == null) {
            return Optional.empty();
        }
        BatchQuoteKey cacheKey = BatchQuoteKey.from(request);
        QuoteResolution resolution = plan.quoteCache().computeIfAbsent(
                cacheKey,
                ignored -> resolveAndStage(request, plan.stagedDocuments())
        );
        return resolution.quote();
    }

    public void persistFetchedQuotes(BatchQuotePlan plan) {
        if (plan == null || plan.stagedDocuments().isEmpty()) {
            return;
        }
        historicalPriceCacheService.storeQuotes(plan.stagedDocuments().values());
    }

    private QuoteResolution resolveAndStage(
            PriceRequest request,
            ConcurrentMap<String, HistoricalPriceDocument> stagedDocuments
    ) {
        Optional<PriceQuote> resolved = priceExternalSourceOrchestrator.resolveExternalOnly(request);
        resolved.ifPresent(priceQuote -> {
            HistoricalPriceDocument document = historicalPriceCacheService.toDocument(request, priceQuote);
            stagedDocuments.putIfAbsent(document.getId(), document);
        });
        return new QuoteResolution(resolved);
    }

    private void prefetchMissingQuotes(
            Map<BatchQuoteKey, PriceRequest> requestsByKey,
            ConcurrentMap<BatchQuoteKey, QuoteResolution> quoteCache,
            ConcurrentMap<String, HistoricalPriceDocument> stagedDocuments
    ) {
        List<Map.Entry<BatchQuoteKey, PriceRequest>> missingRequests = requestsByKey.entrySet().stream()
                .filter(entry -> !quoteCache.containsKey(entry.getKey()))
                .toList();
        if (missingRequests.isEmpty()) {
            return;
        }

        int lanes = Math.max(1, Math.min(pricingProperties.getQuoteResolveParallelLanes(), missingRequests.size()));
        if (lanes == 1 || missingRequests.size() == 1) {
            resolveMissingPartition(missingRequests, quoteCache, stagedDocuments);
            return;
        }

        List<List<Map.Entry<BatchQuoteKey, PriceRequest>>> partitions = partitionMissingRequests(missingRequests, lanes);
        List<CompletableFuture<Void>> futures = partitions.stream()
                .map(partition -> CompletableFuture.runAsync(
                        () -> resolveMissingPartition(partition, quoteCache, stagedDocuments),
                        pricingExecutor
                ))
                .toList();
        futures.forEach(CompletableFuture::join);
    }

    private void resolveMissingPartition(
            List<Map.Entry<BatchQuoteKey, PriceRequest>> partition,
            ConcurrentMap<BatchQuoteKey, QuoteResolution> quoteCache,
            ConcurrentMap<String, HistoricalPriceDocument> stagedDocuments
    ) {
        for (Map.Entry<BatchQuoteKey, PriceRequest> entry : partition) {
            quoteCache.computeIfAbsent(
                    entry.getKey(),
                    ignored -> resolveAndStage(entry.getValue(), stagedDocuments)
            );
        }
    }

    private List<List<Map.Entry<BatchQuoteKey, PriceRequest>>> partitionMissingRequests(
            List<Map.Entry<BatchQuoteKey, PriceRequest>> missingRequests,
            int lanes
    ) {
        List<List<Map.Entry<BatchQuoteKey, PriceRequest>>> partitions = new ArrayList<>(lanes);
        int partitionSize = (int) Math.ceil((double) missingRequests.size() / lanes);
        for (int start = 0; start < missingRequests.size(); start += partitionSize) {
            partitions.add(missingRequests.subList(start, Math.min(missingRequests.size(), start + partitionSize)));
        }
        return partitions;
    }

    private Map<BatchQuoteKey, PriceRequest> extractUniqueRequests(List<NormalizedTransaction> batch) {
        Map<BatchQuoteKey, PriceRequest> requestsByKey = new LinkedHashMap<>();
        if (batch == null || batch.isEmpty()) {
            return requestsByKey;
        }

        for (NormalizedTransaction transaction : batch) {
            if (transaction == null || transaction.getFlows() == null) {
                continue;
            }
            for (int flowIndex = 0; flowIndex < transaction.getFlows().size(); flowIndex++) {
                NormalizedTransaction.Flow flow = transaction.getFlows().get(flowIndex);
                if (!PriceableFlowPolicy.requiresMarketPrice(transaction, flow)) {
                    continue;
                }
                PriceRequest request = new PriceResolutionContext(
                        transaction,
                        flow,
                        flowIndex,
                        Map.of()
                ).toPriceRequest();
                requestsByKey.putIfAbsent(BatchQuoteKey.from(request), request);
            }
        }
        return requestsByKey;
    }

    static record BatchQuotePlan(
            ConcurrentMap<BatchQuoteKey, QuoteResolution> quoteCache,
            ConcurrentMap<String, HistoricalPriceDocument> stagedDocuments
    ) {
        public static BatchQuotePlan empty() {
            return new BatchQuotePlan(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private record QuoteResolution(Optional<PriceQuote> quote) {
    }

    private record BatchQuoteKey(String assetKey, Instant bucketStart) {
        private static BatchQuoteKey from(PriceRequest request) {
            return new BatchQuoteKey(
                    request.assetKey(),
                    request.occurredAt().truncatedTo(ChronoUnit.MINUTES)
            );
        }
    }
}
