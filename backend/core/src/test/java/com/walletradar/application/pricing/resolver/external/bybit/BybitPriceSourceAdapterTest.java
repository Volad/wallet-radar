package com.walletradar.application.pricing.resolver.external.bybit;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.PriceRequest;
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
class BybitPriceSourceAdapterTest {

    @Mock
    private BybitSymbolMapper symbolMapper;
    @Mock
    private BybitKlineClient klineClient;

    @Test
    void bybitRowResolvesFromBybitMarketData() {
        PriceRequest request = new PriceRequest(
                "BYBIT:tx-1",
                NormalizedTransactionSource.BYBIT,
                null,
                null,
                "MNT",
                Instant.parse("2025-01-14T12:43:33Z")
        );
        when(symbolMapper.candidateSymbols(request)).thenReturn(List.of("MNTUSDT"));
        when(klineClient.fetchKline("MNTUSDT", request.occurredAt())).thenReturn(Optional.of(
                new BybitKlineClient.BybitKline(
                        "MNTUSDT",
                        Instant.parse("2025-01-14T12:43:00Z"),
                        new BigDecimal("1.05")
                )
        ));

        BybitPriceSourceAdapter adapter = new BybitPriceSourceAdapter(symbolMapper, klineClient);

        assertThat(adapter.resolve(request))
                .isPresent()
                .get()
                .extracting(quote -> quote.source(), quote -> quote.unitPriceUsd())
                .containsExactly(PriceSource.BYBIT, new BigDecimal("1.05"));
    }

    @Test
    void onChainRowCanAlsoResolveFromBybitMarketData() {
        PriceRequest request = new PriceRequest(
                "tx-1",
                NormalizedTransactionSource.ON_CHAIN,
                null,
                null,
                "MNT",
                Instant.parse("2025-01-14T12:43:33Z")
        );
        when(symbolMapper.candidateSymbols(request)).thenReturn(List.of("MNTUSDT"));
        when(klineClient.fetchKline("MNTUSDT", request.occurredAt())).thenReturn(Optional.of(
                new BybitKlineClient.BybitKline(
                        "MNTUSDT",
                        Instant.parse("2025-01-14T12:43:00Z"),
                        new BigDecimal("1.05")
                )
        ));

        BybitPriceSourceAdapter adapter = new BybitPriceSourceAdapter(symbolMapper, klineClient);

        assertThat(adapter.resolve(request))
                .isPresent()
                .get()
                .extracting(quote -> quote.source(), quote -> quote.unitPriceUsd())
                .containsExactly(PriceSource.BYBIT, new BigDecimal("1.05"));
    }
}
