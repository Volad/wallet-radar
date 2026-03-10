package com.walletradar.pricing.resolver;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeAssetResolverTest {

    @Test
    @DisplayName("zksync pseudo native aliases to ethereum canonical contract before CoinGecko lookup")
    void zksyncPseudoNativeAliasesToEthereumCanonicalContract() {
        CoinGeckoHistoricalResolver coinGeckoHistoricalResolver = mock(CoinGeckoHistoricalResolver.class);
        when(coinGeckoHistoricalResolver.resolve(any()))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("2500"), PriceSource.COINGECKO));

        NativeAssetResolver resolver = new NativeAssetResolver(coinGeckoHistoricalResolver);
        HistoricalPriceRequest request = new HistoricalPriceRequest();
        request.setAssetContract("0x000000000000000000000000000000000000800a");
        request.setNetworkId(NetworkId.ZKSYNC);
        request.setBlockTimestamp(Instant.parse("2026-02-01T00:00:00Z"));

        PriceResolutionResult result = resolver.resolve(request);

        assertThat(result.isUnknown()).isFalse();
        ArgumentCaptor<HistoricalPriceRequest> captor = ArgumentCaptor.forClass(HistoricalPriceRequest.class);
        verify(coinGeckoHistoricalResolver).resolve(captor.capture());
        assertThat(captor.getValue().getAssetContract())
                .isEqualTo("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
    }

    @Test
    @DisplayName("wrapped native aliases on L2s resolve via ethereum canonical contract")
    void wrappedNativeAliasesResolveViaEthereumCanonicalContract() {
        CoinGeckoHistoricalResolver coinGeckoHistoricalResolver = mock(CoinGeckoHistoricalResolver.class);
        when(coinGeckoHistoricalResolver.resolve(any()))
                .thenReturn(PriceResolutionResult.known(new BigDecimal("2500"), PriceSource.COINGECKO));

        NativeAssetResolver resolver = new NativeAssetResolver(coinGeckoHistoricalResolver);

        HistoricalPriceRequest arbitrumWeth = new HistoricalPriceRequest();
        arbitrumWeth.setAssetContract("0x82af49447d8a07e3bd95bd0d56f35241523fbab1");
        arbitrumWeth.setNetworkId(NetworkId.ARBITRUM);
        arbitrumWeth.setBlockTimestamp(Instant.parse("2026-02-01T00:00:00Z"));

        HistoricalPriceRequest baseWeth = new HistoricalPriceRequest();
        baseWeth.setAssetContract("0x4200000000000000000000000000000000000006");
        baseWeth.setNetworkId(NetworkId.BASE);
        baseWeth.setBlockTimestamp(Instant.parse("2026-02-01T00:00:00Z"));

        resolver.resolve(arbitrumWeth);
        resolver.resolve(baseWeth);

        ArgumentCaptor<HistoricalPriceRequest> captor = ArgumentCaptor.forClass(HistoricalPriceRequest.class);
        verify(coinGeckoHistoricalResolver, org.mockito.Mockito.times(2)).resolve(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(HistoricalPriceRequest::getAssetContract)
                .containsExactly(
                        "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                );
    }
}
