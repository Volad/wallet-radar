package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalPriceCacheServiceTest {

    @Mock
    private HistoricalPriceRepository historicalPriceRepository;

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

        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository);
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
    void storeQuoteUsesDeterministicIdAndMinuteBucket() {
        when(historicalPriceRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository);

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
        HistoricalPriceCacheService service = new HistoricalPriceCacheService(historicalPriceRepository);

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
}
