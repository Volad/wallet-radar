package com.walletradar.pricing.resolver.external;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
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

    @Test
    void cacheHitWinsBeforeExternalLookup() {
        PriceRequest request = request();
        PriceQuote cached = quote(PriceSource.BINANCE, "100");
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.of(cached));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(primarySource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(cached);
        verify(primarySource, never()).resolve(request);
    }

    @Test
    void fallbackSourceRunsAfterPrimaryMiss() {
        PriceRequest request = request();
        PriceQuote fallbackQuote = quote(PriceSource.COINGECKO, "101");
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.empty());
        when(historicalPriceCacheService.findQuote(request, PriceSource.COINGECKO)).thenReturn(Optional.empty());
        when(primarySource.resolve(request)).thenReturn(Optional.empty());
        when(fallbackSource.resolve(request)).thenReturn(Optional.of(fallbackQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(primarySource, fallbackSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(fallbackQuote);
        verify(historicalPriceCacheService).storeQuote(request, fallbackQuote);
    }

    @Test
    void exceptionInPrimaryDoesNotStopFallback() {
        PriceRequest request = request();
        PriceQuote fallbackQuote = quote(PriceSource.COINGECKO, "99");
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.empty());
        when(historicalPriceCacheService.findQuote(request, PriceSource.COINGECKO)).thenReturn(Optional.empty());
        when(primarySource.resolve(request)).thenThrow(new IllegalStateException("boom"));
        when(fallbackSource.resolve(request)).thenReturn(Optional.of(fallbackQuote));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(primarySource, fallbackSource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(fallbackQuote);
        verify(historicalPriceCacheService).storeQuote(request, fallbackQuote);
    }

    @Test
    void deterministicSourceOrderingUsesBinanceBeforeCoinGecko() {
        PriceRequest request = request();
        PriceQuote binance = quote(PriceSource.BINANCE, "102");
        when(primarySource.source()).thenReturn(PriceSource.BINANCE);
        when(fallbackSource.source()).thenReturn(PriceSource.COINGECKO);
        when(historicalPriceCacheService.findQuote(request, PriceSource.BINANCE)).thenReturn(Optional.empty());
        when(primarySource.resolve(request)).thenReturn(Optional.of(binance));

        PriceExternalSourceOrchestrator orchestrator = new PriceExternalSourceOrchestrator(
                historicalPriceCacheService,
                List.of(fallbackSource, primarySource)
        );

        Optional<PriceQuote> result = orchestrator.resolve(request);

        assertThat(result).contains(binance);
        verify(primarySource).resolve(request);
        verify(fallbackSource, never()).resolve(request);
    }

    private PriceRequest request() {
        return new PriceRequest(
                "tx-123",
                NetworkId.BASE,
                "0xabc",
                "TOKEN",
                Instant.parse("2024-06-01T10:00:00Z")
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
