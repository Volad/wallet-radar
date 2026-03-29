package com.walletradar.pricing.resolver.external.binance;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinancePriceSourceAdapterTest {

    @Mock
    private BinanceSymbolMapper symbolMapper;
    @Mock
    private BinanceKlineClient klineClient;

    @Test
    void listedAssetResolvesFromBinance() {
        PriceRequest request = new PriceRequest("tx-1", NetworkId.BASE, null, "ETH", Instant.parse("2026-03-25T10:15:00Z"));
        when(symbolMapper.candidateSymbols(request)).thenReturn(List.of("ETHUSDT"));
        when(klineClient.fetchKline("ETHUSDT", request.occurredAt())).thenReturn(Optional.of(
                new BinanceKlineClient.BinanceKline(
                        "ETHUSDT",
                        Instant.parse("2026-03-25T10:15:00Z"),
                        new BigDecimal("2000")
                )
        ));

        BinancePriceSourceAdapter adapter = new BinancePriceSourceAdapter(symbolMapper, klineClient);

        assertThat(adapter.resolve(request))
                .isPresent()
                .get()
                .extracting(quote -> quote.source(), quote -> quote.unitPriceUsd())
                .containsExactly(PriceSource.BINANCE, new BigDecimal("2000"));
    }

    @Test
    void unavailableSymbolReturnsEmpty() {
        PriceRequest request = new PriceRequest("tx-1", NetworkId.BASE, null, "XYZ", Instant.parse("2026-03-25T10:15:00Z"));
        when(symbolMapper.candidateSymbols(request)).thenReturn(List.of("XYZUSDT"));
        when(klineClient.fetchKline("XYZUSDT", request.occurredAt())).thenReturn(Optional.empty());

        BinancePriceSourceAdapter adapter = new BinancePriceSourceAdapter(symbolMapper, klineClient);

        assertThat(adapter.resolve(request)).isEmpty();
    }
}
