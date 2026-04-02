package com.walletradar.pricing.resolver.external;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import com.walletradar.pricing.persistence.HistoricalPriceCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceExternalSourceOrchestratorTest {

    @Mock
    private HistoricalPriceCacheService historicalPriceCacheService;
    @Mock
    private ExternalPriceSource primarySource;
    @Mock
    private ExternalPriceSource fallbackSource;
    @Mock
    private ExternalPriceSource bybitSource;
    @Mock
    private ExternalPriceSource ecbSource;

    @Test
    void cacheHitWinsBeforeExternalLookup() {
        PriceRequest request = request();
        PriceQuote cached = quote(PriceSource.BINANCE, "100");
        when(ecbSource.supports(request)).thenReturn(false);
        when(primarySource.supports(request)).thenReturn(true);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.of(cached));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(ecbSource, primarySource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(cached);
        verify(primarySource, never()).resolve(request);
    }

    @Test
    void fallbackSourceRunsAfterPrimaryMiss() {
        PriceRequest request = request();
        PriceQuote fallbackQuote = quote(PriceSource.COINGECKO, "101");
        when(ecbSource.supports(request)).thenReturn(false);
        when(primarySource.supports(request)).thenReturn(true);
        when(fallbackSource.supports(request)).thenReturn(true);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.empty());
        when(historicalPriceCacheService.findQuote(request, PriceSource.COINGECKO)).thenReturn(Optional.empty());
        when(primarySource.resolve(request)).thenReturn(Optional.empty());
        when(fallbackSource.resolve(request)).thenReturn(Optional.of(fallbackQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(ecbSource, primarySource, fallbackSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(fallbackQuote);
        verify(historicalPriceCacheService).storeQuote(request, fallbackQuote);
    }

    @Test
    void exceptionInPrimaryDoesNotStopFallback() {
        PriceRequest request = request();
        PriceQuote fallbackQuote = quote(PriceSource.COINGECKO, "99");
        when(ecbSource.supports(request)).thenReturn(false);
        when(primarySource.supports(request)).thenReturn(true);
        when(fallbackSource.supports(request)).thenReturn(true);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.empty());
        when(historicalPriceCacheService.findQuote(request, PriceSource.COINGECKO)).thenReturn(Optional.empty());
        when(primarySource.resolve(request)).thenThrow(new IllegalStateException("boom"));
        when(fallbackSource.resolve(request)).thenReturn(Optional.of(fallbackQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(ecbSource, primarySource, fallbackSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(fallbackQuote);
        verify(historicalPriceCacheService).storeQuote(request, fallbackQuote);
    }

    @Test
    void nonEuroAssetsUseBybitBeforeBinanceAndCoinGecko() {
        PriceRequest request = request();
        PriceQuote bybit = quote(PriceSource.BYBIT, "102");
        when(ecbSource.supports(request)).thenReturn(false);
        when(bybitSource.supports(request)).thenReturn(true);
        when(primarySource.supports(request)).thenReturn(true);
        when(fallbackSource.supports(request)).thenReturn(true);
        when(bybitSource.source()).thenReturn(PriceSource.BYBIT);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BYBIT)).thenReturn(Optional.empty());
        when(bybitSource.resolve(request)).thenReturn(Optional.of(bybit));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(fallbackSource, primarySource, bybitSource, ecbSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(bybit);
        verify(bybitSource).resolve(request);
        verify(primarySource, never()).resolve(request);
        verify(fallbackSource, never()).resolve(request);
    }

    @Test
    void bybitRowsUseBybitBeforeBinanceAndCoinGecko() {
        PriceRequest request = bybitRequest();
        PriceQuote bybitQuote = quote(PriceSource.BYBIT, "102");
        when(ecbSource.supports(request)).thenReturn(false);
        when(bybitSource.supports(request)).thenReturn(true);
        when(primarySource.supports(request)).thenReturn(true);
        when(fallbackSource.supports(request)).thenReturn(true);
        when(bybitSource.source()).thenReturn(PriceSource.BYBIT);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BYBIT)).thenReturn(Optional.empty());
        when(bybitSource.resolve(request)).thenReturn(Optional.of(bybitQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(fallbackSource, primarySource, bybitSource, ecbSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(bybitQuote);
        verify(bybitSource).resolve(request);
        verify(primarySource, never()).resolve(request);
        verify(fallbackSource, never()).resolve(request);
    }

    @Test
    void euroStableAssetsUseEcbBeforeExchangeSources() {
        PriceRequest request = euroStableRequest();
        PriceQuote ecbQuote = quote(PriceSource.ECB, "1.03");
        when(ecbSource.supports(request)).thenReturn(true);
        when(bybitSource.supports(request)).thenReturn(true);
        when(primarySource.supports(request)).thenReturn(true);
        when(fallbackSource.supports(request)).thenReturn(true);
        when(ecbSource.source()).thenReturn(PriceSource.ECB);
        when(bybitSource.source()).thenReturn(PriceSource.BYBIT);
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.ECB)).thenReturn(Optional.empty());
        when(ecbSource.resolve(request)).thenReturn(Optional.of(ecbQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(fallbackSource, primarySource, bybitSource, ecbSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(ecbQuote);
        verify(ecbSource).resolve(request);
        verify(bybitSource, never()).resolve(request);
        verify(primarySource, never()).resolve(request);
        verify(fallbackSource, never()).resolve(request);
    }

    private PriceRequest request() {
        return new PriceRequest(
                "tx-123",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.BASE,
                "0xabc",
                "TOKEN",
                Instant.parse("2024-06-01T10:00:00Z")
        );
    }

    private PriceRequest bybitRequest() {
        return new PriceRequest(
                "BYBIT:tx-123",
                NormalizedTransactionSource.BYBIT,
                null,
                null,
                "MNT",
                Instant.parse("2025-01-14T12:43:33Z")
        );
    }

    private PriceRequest euroStableRequest() {
        return new PriceRequest(
                "tx-eurc",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.AVALANCHE,
                "0xc891eb4cbdeff6e073e859e987815ed1505c2acd",
                "EURC",
                Instant.parse("2025-01-14T12:43:33Z")
        );
    }

    private PriceQuote quote(PriceSource source, String price) {
        return new PriceQuote(
                new BigDecimal(price),
                source,
                Instant.parse("2024-06-01T10:00:00Z"),
                "USD",
                source.name().toLowerCase()
        );
    }
}
