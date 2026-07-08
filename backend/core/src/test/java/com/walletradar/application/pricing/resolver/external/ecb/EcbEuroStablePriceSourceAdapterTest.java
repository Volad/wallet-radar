package com.walletradar.application.pricing.resolver.external.ecb;

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
class EcbEuroStablePriceSourceAdapterTest {

    @Mock
    private EcbFxHistoricalClient historicalClient;

    @Test
    void euroStableResolvesFromEcbFx() {
        PriceRequest request = new PriceRequest(
                "tx-eurc",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.AVALANCHE,
                "0xc891eb4cbdeff6e073e859e987815ed1505c2acd",
                "EURC",
                Instant.parse("2025-01-14T12:43:33Z")
        );
        when(historicalClient.fetchEurUsd(request.occurredAt())).thenReturn(Optional.of(
                new EcbFxHistoricalClient.EcbFxQuote(
                        Instant.parse("2025-01-14T00:00:00Z"),
                        new BigDecimal("1.0245")
                )
        ));

        EcbEuroStablePriceSourceAdapter adapter = new EcbEuroStablePriceSourceAdapter(historicalClient);

        assertThat(adapter.supports(request)).isTrue();
        assertThat(adapter.resolve(request))
                .isPresent()
                .get()
                .extracting(quote -> quote.source(), quote -> quote.unitPriceUsd())
                .containsExactly(PriceSource.ECB, new BigDecimal("1.0245"));
    }

    @Test
    void nonEuroAssetIsNotSupported() {
        PriceRequest request = new PriceRequest(
                "tx-mnt",
                NormalizedTransactionSource.ON_CHAIN,
                NetworkId.MANTLE,
                null,
                "MNT",
                Instant.parse("2025-01-14T12:43:33Z")
        );

        EcbEuroStablePriceSourceAdapter adapter = new EcbEuroStablePriceSourceAdapter(historicalClient);

        assertThat(adapter.supports(request)).isFalse();
    }
}
