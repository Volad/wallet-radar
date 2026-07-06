package com.walletradar.pricing.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import com.walletradar.pricing.persistence.HistoricalPriceDocument;
import com.walletradar.pricing.persistence.HistoricalPriceRepository;
import com.mongodb.bulk.BulkWriteResult;
import com.walletradar.pricing.resolver.external.PriceExternalSourceOrchestrator;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchPriceQuoteResolverTest {

    @Mock
    private HistoricalPriceRepository historicalPriceRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private BulkOperations bulkOperations;
    @Mock
    private PriceExternalSourceOrchestrator priceExternalSourceOrchestrator;

    private final PricingProperties pricingProperties = pricingProperties();

    @Test
    void prepareSeedsWarmCacheWithoutExternalLookup() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );
        NormalizedTransaction transaction = pendingTransaction("tx-1");
        PriceRequest request = priceRequest(transaction);

        HistoricalPriceDocument cachedDocument = cacheService.toDocument(
                request,
                new PriceQuote(
                        new BigDecimal("123.45"),
                        PriceSource.BINANCE,
                        Instant.parse("2026-03-25T10:00:00Z"),
                        "USD",
                        "TOKENUSDT"
                )
        );

        when(priceExternalSourceOrchestrator.prioritizedSources(any())).thenReturn(List.of(PriceSource.BINANCE));
        when(historicalPriceRepository.findAllById(anyCollection())).thenReturn(List.of(cachedDocument));

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(transaction));
        Optional<PriceQuote> resolved = resolver.resolve(request, plan);
        resolver.persistFetchedQuotes(plan);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().unitPriceUsd()).isEqualByComparingTo("123.45");
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    void prepareSkipsStablecoinParityRowsFromExternalPrefetch() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(bybitStablecoinTransfer("tx-usdt")));

        assertThat(plan.quoteCache()).isEmpty();
        assertThat(plan.stagedDocuments()).isEmpty();
        verify(priceExternalSourceOrchestrator, never()).prioritizedSources(any());
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
    }

    @Test
    void prepareSkipsOnChainStablecoinParityRowsFromExternalPrefetch() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(onChainStablecoinTransfer("tx-usdt")));

        assertThat(plan.quoteCache()).isEmpty();
        assertThat(plan.stagedDocuments()).isEmpty();
        verify(priceExternalSourceOrchestrator, never()).prioritizedSources(any());
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
    }

    @Test
    void prepareSkipsFlowsWithoutAssetSymbol() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );
        NormalizedTransaction transaction = pendingTransaction("tx-no-symbol");
        transaction.getFlows().getFirst().setAssetSymbol(null);

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(transaction));

        assertThat(plan.quoteCache()).isEmpty();
        assertThat(plan.stagedDocuments()).isEmpty();
        verify(priceExternalSourceOrchestrator, never()).prioritizedSources(any());
        verify(priceExternalSourceOrchestrator, never()).resolveExternalOnly(any());
    }

    @Test
    void preparePrefetchesAsyncDexOrderRequestPrincipalQuote() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );
        NormalizedTransaction transaction = pendingDexOrderRequest("tx-dex-request");
        PriceRequest request = priceRequest(transaction);

        when(priceExternalSourceOrchestrator.prioritizedSources(any())).thenReturn(List.of(PriceSource.BYBIT));
        when(historicalPriceRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(priceExternalSourceOrchestrator.resolveExternalOnly(any())).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("2500"),
                PriceSource.BYBIT,
                Instant.parse("2026-03-25T10:00:00Z"),
                "USD",
                "ETHUSDT"
        )));

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(transaction));
        Optional<PriceQuote> resolved = resolver.resolve(request, plan);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().unitPriceUsd()).isEqualByComparingTo("2500");
        assertThat(plan.stagedDocuments()).hasSize(1);
        verify(priceExternalSourceOrchestrator, times(1)).resolveExternalOnly(any());
    }

    @Test
    void resolveDeduplicatesMissingQuoteFetchAndPersistsNewQuotesInBulk() {
        HistoricalPriceCacheService cacheService = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        BatchPriceQuoteResolver resolver = new BatchPriceQuoteResolver(
                cacheService,
                priceExternalSourceOrchestrator,
                pricingProperties,
                directExecutor()
        );
        NormalizedTransaction first = pendingTransaction("tx-1");
        NormalizedTransaction second = pendingTransaction("tx-2");
        PriceRequest request = priceRequest(first);
        String expectedDocumentId = cacheService.documentId(request, PriceSource.BINANCE);

        when(priceExternalSourceOrchestrator.prioritizedSources(any())).thenReturn(List.of(PriceSource.BINANCE));
        when(historicalPriceRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(priceExternalSourceOrchestrator.resolveExternalOnly(any())).thenReturn(Optional.of(new PriceQuote(
                new BigDecimal("222.22"),
                PriceSource.BINANCE,
                Instant.parse("2026-03-25T10:00:00Z"),
                "USD",
                "TOKENUSDT"
        )));
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, HistoricalPriceDocument.class))
                .thenReturn(bulkOperations);
        when(bulkOperations.upsert(any(), any(Update.class))).thenReturn(bulkOperations);
        when(bulkOperations.execute()).thenReturn(mock(BulkWriteResult.class));

        BatchPriceQuoteResolver.BatchQuotePlan plan = resolver.prepare(List.of(first, second));
        Optional<PriceQuote> firstResolved = resolver.resolve(request, plan);
        Optional<PriceQuote> secondResolved = resolver.resolve(request, plan);
        resolver.persistFetchedQuotes(plan);

        assertThat(firstResolved).isPresent();
        assertThat(secondResolved).isPresent();
        verify(priceExternalSourceOrchestrator, times(1)).resolveExternalOnly(any());

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(bulkOperations).upsert(queryCaptor.capture(), updateCaptor.capture());
        verify(bulkOperations).execute();
        assertThat(queryCaptor.getValue().getQueryObject().getString("_id")).isEqualTo(expectedDocumentId);
        Document setDocument = updateCaptor.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(setDocument.getString("assetKey")).isEqualTo("BASE:0xasset");
    }

    private PricingProperties pricingProperties() {
        PricingProperties properties = new PricingProperties();
        properties.setQuoteResolveParallelLanes(4);
        return properties;
    }

    private Executor directExecutor() {
        return Runnable::run;
    }

    private NormalizedTransaction pendingTransaction(String id) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash("0xhash-" + id);
        transaction.setNetworkId(NetworkId.BASE);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.SWAP);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:40Z"));
        transaction.setFlows(List.of(flow()));
        return transaction;
    }

    private NormalizedTransaction bybitStablecoinTransfer(String id) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash("0xhash-" + id);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("BYBIT:33625378");
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-02-02T06:15:03Z"));
        transaction.setFlows(List.of(stablecoinFlow()));
        return transaction;
    }

    private NormalizedTransaction onChainStablecoinTransfer(String id) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash("0xhash-" + id);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-02-02T06:15:03Z"));
        transaction.setFlows(List.of(stablecoinFlow()));
        return transaction;
    }

    private NormalizedTransaction pendingDexOrderRequest(String id) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId(id);
        transaction.setTxHash("0xhash-" + id);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.DEX_ORDER_REQUEST);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-25T10:00:40Z"));
        transaction.setCorrelationId("cow-order:1");
        transaction.setFlows(List.of(
                flow(),
                feeFlow()
        ));
        return transaction;
    }

    private NormalizedTransaction.Flow flow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.SELL);
        flow.setAssetContract("0xasset");
        flow.setAssetSymbol("TOKEN");
        flow.setQuantityDelta(new BigDecimal("-1"));
        return flow;
    }

    private NormalizedTransaction.Flow feeFlow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.FEE);
        flow.setAssetContract("0xasset");
        flow.setAssetSymbol("TOKEN");
        flow.setQuantityDelta(new BigDecimal("-0.01"));
        return flow;
    }

    private NormalizedTransaction.Flow stablecoinFlow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.SELL);
        flow.setAssetSymbol("USDT");
        flow.setQuantityDelta(new BigDecimal("-220"));
        return flow;
    }

    private PriceRequest priceRequest(NormalizedTransaction transaction) {
        return new PriceRequest(
                transaction.getId(),
                transaction.getSource(),
                transaction.getNetworkId(),
                transaction.getFlows().getFirst().getAssetContract(),
                transaction.getFlows().getFirst().getAssetSymbol(),
                transaction.getBlockTimestamp()
        );
    }
}
