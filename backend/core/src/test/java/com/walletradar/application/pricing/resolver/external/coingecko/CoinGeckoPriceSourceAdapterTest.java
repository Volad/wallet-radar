package com.walletradar.application.pricing.resolver.external.coingecko;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.pricing.domain.PriceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoinGeckoPriceSourceAdapterTest {

    @Mock
    private CoinGeckoAssetMapper assetMapper;
    @Mock
    private CoinGeckoHistoricalClient historicalClient;

    @Test
    void fallbackResolvesWhenCoinIdExists() {
        PriceRequest request = new PriceRequest(
                "tx-1",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.BASE,
                null,
                "CAKE",
                Instant.parse("2026-03-25T10:15:00Z")
        );
        when(assetMapper.coinId(request)).thenReturn(Optional.of("pancakeswap-token"));
        when(historicalClient.fetchHistory("pancakeswap-token", request.occurredAt())).thenReturn(Optional.of(
                new CoinGeckoHistoricalClient.CoinGeckoHistory(
                        "pancakeswap-token",
                        request.occurredAt(),
                        new BigDecimal("2.50")
                )
        ));

        CoinGeckoPriceSourceAdapter adapter = new CoinGeckoPriceSourceAdapter(assetMapper, historicalClient);

        assertThat(adapter.resolve(request))
                .isPresent()
                .get()
                .extracting(quote -> quote.source(), quote -> quote.unitPriceUsd())
                .containsExactly(PriceSource.COINGECKO, new BigDecimal("2.50"));
    }

    @Test
    void missingCoinIdReturnsEmpty() {
        PriceRequest request = new PriceRequest(
                "tx-1",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.BASE,
                null,
                "XYZ",
                Instant.parse("2026-03-25T10:15:00Z")
        );
        when(assetMapper.coinId(request)).thenReturn(Optional.empty());

        CoinGeckoPriceSourceAdapter adapter = new CoinGeckoPriceSourceAdapter(assetMapper, historicalClient);

        assertThat(adapter.resolve(request)).isEmpty();
    }
}
