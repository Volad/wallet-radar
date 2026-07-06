package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.mongodb.bulk.BulkWriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalPriceCacheServiceTest {

    @Mock
    private HistoricalPriceRepository historicalPriceRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private BulkOperations bulkOperations;

    @Test
    void findQuoteReturnsCachedQuote() {
        HistoricalPriceDocument document = new HistoricalPriceDocument();
        document.setId("BASE:0xabc:1717243200000:BINANCE");
        document.setAssetKey("BASE:0xabc");
        document.setBucketStart(Instant.parse("2024-06-01T10:00:00Z"));
        document.setSource(PriceSource.BINANCE);
        document.setPriceUsd(new BigDecimal("123.45"));
        document.setQuoteSymbol("USDT");

        when(historicalPriceRepository.findByAssetKeyAndBucketStartAndSource(
                "BASE:0xabc",
                Instant.parse("2024-06-01T10:00:00Z"),
                PriceSource.BINANCE
        )).thenReturn(Optional.of(document));

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        Optional<PriceQuote> quote = service.findQuote(new PriceRequest(
                "tx-1",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.BASE,
                "0xabc",
                "TOKEN",
                Instant.parse("2024-06-01T10:00:40Z")
        ), PriceSource.BINANCE);

        assertThat(quote).isPresent();
        assertThat(quote.orElseThrow().unitPriceUsd()).isEqualByComparingTo("123.45");
        assertThat(quote.orElseThrow().source()).isEqualTo(PriceSource.BINANCE);
    }

    @Test
    void findNearestQuoteWithinWindowClampsPreCoverageRequestToEarliestBucket() {
        // RC-D: a 2025-01-31 DOGE lot that predates the asset's first cached bucket (2025-09-22)
        // must clamp to the nearest valid bucket rather than a far out-of-range value.
        HistoricalPriceDocument firstBucket = new HistoricalPriceDocument();
        firstBucket.setId("GLOBAL:SYMBOL:DOGE:x:BINANCE");
        firstBucket.setAssetKey("GLOBAL:SYMBOL:DOGE");
        firstBucket.setBucketStart(Instant.parse("2025-09-22T00:00:00Z"));
        firstBucket.setSource(PriceSource.BINANCE);
        firstBucket.setPriceUsd(new BigDecimal("0.32"));
        firstBucket.setQuoteSymbol("USDT");

        Instant target = Instant.parse("2025-01-31T12:00:00Z").truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.of(firstBucket));
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.empty());

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        Optional<PriceQuote> quote = service.findNearestQuoteWithinWindow(
                new PriceRequest("tx-doge", NormalizedTransactionSource.BYBIT, null, null, "DOGE",
                        Instant.parse("2025-01-31T12:00:00Z")),
                PriceSource.BINANCE,
                java.time.Duration.ofDays(400)
        );

        assertThat(quote).isPresent();
        assertThat(quote.orElseThrow().unitPriceUsd()).isEqualByComparingTo("0.32");
    }

    @Test
    void findNearestQuoteWithinWindowFailsSafeWhenNearestBucketOutsideWindow() {
        HistoricalPriceDocument farBucket = new HistoricalPriceDocument();
        farBucket.setAssetKey("GLOBAL:SYMBOL:DOGE");
        farBucket.setBucketStart(Instant.parse("2025-09-22T00:00:00Z"));
        farBucket.setSource(PriceSource.BINANCE);
        farBucket.setPriceUsd(new BigDecimal("0.32"));

        Instant target = Instant.parse("2020-01-01T00:00:00Z").truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.of(farBucket));
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.empty());

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        Optional<PriceQuote> quote = service.findNearestQuoteWithinWindow(
                new PriceRequest("tx-doge", NormalizedTransactionSource.BYBIT, null, null, "DOGE",
                        Instant.parse("2020-01-01T00:00:00Z")),
                PriceSource.BINANCE,
                java.time.Duration.ofDays(400)
        );

        assertThat(quote).isEmpty();
    }

    @Test
    void findPreCoverageNearestQuoteClampsWhenNoBucketAtOrBeforeEvent() {
        // RC-D: a pre-coverage bot lot (2025-01-31, before the 2025-09-22 first bucket) has no
        // bucket at/before the event minute → clamp to the nearest forward bucket within window.
        HistoricalPriceDocument firstBucket = new HistoricalPriceDocument();
        firstBucket.setAssetKey("GLOBAL:SYMBOL:DOGE");
        firstBucket.setBucketStart(Instant.parse("2025-09-22T00:00:00Z"));
        firstBucket.setSource(PriceSource.BINANCE);
        firstBucket.setPriceUsd(new BigDecimal("0.23246"));
        firstBucket.setQuoteSymbol("USDT");

        Instant target = Instant.parse("2025-01-31T12:00:00Z").truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.empty());
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.of(firstBucket));

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        Optional<PriceQuote> quote = service.findPreCoverageNearestQuote(
                new PriceRequest("tx-doge", NormalizedTransactionSource.BYBIT, null, null, "DOGE",
                        Instant.parse("2025-01-31T12:00:00Z")),
                PriceSource.BINANCE,
                java.time.Duration.ofDays(400)
        );

        assertThat(quote).isPresent();
        assertThat(quote.orElseThrow().unitPriceUsd()).isEqualByComparingTo("0.23246");
    }

    @Test
    void findPreCoverageNearestQuoteReturnsEmptyForInCoverageEvent() {
        // An in-coverage lot (a bucket exists at/before the event minute) must NOT be clamped — the
        // bot-derived price stands, or the regular market minute applies elsewhere.
        HistoricalPriceDocument beforeBucket = new HistoricalPriceDocument();
        beforeBucket.setAssetKey("GLOBAL:SYMBOL:DOGE");
        beforeBucket.setBucketStart(Instant.parse("2025-10-01T00:00:00Z"));
        beforeBucket.setSource(PriceSource.BINANCE);
        beforeBucket.setPriceUsd(new BigDecimal("0.30"));

        Instant target = Instant.parse("2025-10-05T12:00:00Z").truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        when(historicalPriceRepository.findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                "GLOBAL:SYMBOL:DOGE", PriceSource.BINANCE, target))
                .thenReturn(Optional.of(beforeBucket));

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);
        Optional<PriceQuote> quote = service.findPreCoverageNearestQuote(
                new PriceRequest("tx-doge", NormalizedTransactionSource.BYBIT, null, null, "DOGE",
                        Instant.parse("2025-10-05T12:00:00Z")),
                PriceSource.BINANCE,
                java.time.Duration.ofDays(400)
        );

        assertThat(quote).isEmpty();
        verify(historicalPriceRepository, never())
                .findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                        any(), any(), any());
    }

    @Test
    void storeQuoteUsesDeterministicIdAndMinuteBucket() {
        when(historicalPriceRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);

        service.storeQuote(
                new PriceRequest(
                        "tx-2",
                        NormalizedTransactionSource.ON_CHAIN,
                        NetworkId.ARBITRUM,
                        "0xdef",
                        "TOKEN",
                        Instant.parse("2024-06-01T10:00:40Z")
                ),
                new PriceQuote(
                        new BigDecimal("1.50"),
                        PriceSource.COINGECKO,
                        Instant.parse("2024-06-01T10:02:00Z"),
                        "USD",
                        "cg:token"
                )
        );

        ArgumentCaptor<HistoricalPriceDocument> captor = ArgumentCaptor.forClass(HistoricalPriceDocument.class);
        verify(historicalPriceRepository).save(captor.capture());
        HistoricalPriceDocument saved = captor.getValue();
        assertThat(saved.getBucketStart()).isEqualTo(Instant.parse("2024-06-01T10:00:00Z"));
        assertThat(saved.getId()).isEqualTo("ARBITRUM:0xdef:1717236000000:COINGECKO");
        assertThat(saved.getSource()).isEqualTo(PriceSource.COINGECKO);
        assertThat(saved.getPriceUsd()).isEqualByComparingTo("1.50");
    }

    @Test
    void storeQuoteUsesGlobalScopeWhenNetworkIdIsMissing() {
        when(historicalPriceRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);

        service.storeQuote(
                new PriceRequest(
                        "tx-3",
                        NormalizedTransactionSource.BYBIT,
                        null,
                        null,
                        "ETH",
                        Instant.parse("2024-06-01T10:00:40Z")
                ),
                new PriceQuote(
                        new BigDecimal("2500"),
                        PriceSource.BINANCE,
                        Instant.parse("2024-06-01T10:02:00Z"),
                        "USD",
                        "ETHUSDT"
                )
        );

        ArgumentCaptor<HistoricalPriceDocument> captor = ArgumentCaptor.forClass(HistoricalPriceDocument.class);
        verify(historicalPriceRepository).save(captor.capture());
        HistoricalPriceDocument saved = captor.getValue();
        assertThat(saved.getAssetKey()).isEqualTo("GLOBAL:SYMBOL:ETH");
        assertThat(saved.getId()).isEqualTo("GLOBAL:SYMBOL:ETH:1717236000000:BINANCE");
        assertThat(saved.getNetworkId()).isNull();
    }

    @Test
    void storeQuotesUsesMongoBulkUpsertInsteadOfRepositorySaveAll() {
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, HistoricalPriceDocument.class))
                .thenReturn(bulkOperations);
        when(bulkOperations.upsert(any(), any(Update.class))).thenReturn(bulkOperations);
        when(bulkOperations.execute()).thenReturn(mock(BulkWriteResult.class));
        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository, mongoTemplate);

        HistoricalPriceDocument first = new HistoricalPriceDocument();
        first.setId("BASE:0xabc:1717236000000:BYBIT");
        first.setAssetKey("BASE:0xabc");
        first.setNetworkId(NetworkId.BASE);
        first.setSymbol("TOKEN");
        first.setBucketStart(Instant.parse("2024-06-01T10:00:00Z"));
        first.setBucketResolution(com.walletradar.pricing.domain.PriceBucketResolution.MINUTE);
        first.setSource(PriceSource.BYBIT);
        first.setPriceUsd(new BigDecimal("1.23"));
        first.setQuoteSymbol("USDT");
        first.setFetchedAt(Instant.parse("2024-06-01T10:00:00Z"));

        HistoricalPriceDocument duplicate = new HistoricalPriceDocument();
        duplicate.setId(first.getId());
        duplicate.setAssetKey(first.getAssetKey());
        duplicate.setNetworkId(first.getNetworkId());
        duplicate.setSymbol(first.getSymbol());
        duplicate.setBucketStart(first.getBucketStart());
        duplicate.setBucketResolution(first.getBucketResolution());
        duplicate.setSource(first.getSource());
        duplicate.setPriceUsd(new BigDecimal("1.24"));
        duplicate.setQuoteSymbol(first.getQuoteSymbol());
        duplicate.setFetchedAt(first.getFetchedAt());

        List<HistoricalPriceDocument> stored = service.storeQuotes(List.of(first, duplicate));

        assertThat(stored).hasSize(1);
        verify(mongoTemplate).bulkOps(BulkOperations.BulkMode.UNORDERED, HistoricalPriceDocument.class);
        verify(bulkOperations).upsert(any(), any(Update.class));
        verify(bulkOperations).execute();
        verify(historicalPriceRepository, never()).saveAll(any());
    }
}
