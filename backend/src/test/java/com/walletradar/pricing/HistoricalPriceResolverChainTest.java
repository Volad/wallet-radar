package com.walletradar.pricing;

import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.resolver.CoinGeckoHistoricalResolver;
import com.walletradar.pricing.resolver.CounterpartPriceResolver;
import com.walletradar.pricing.resolver.StablecoinResolver;
import com.walletradar.pricing.resolver.SwapDerivedResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalPriceResolverChainTest {

    private HistoricalPriceResolverChain chain;

    @BeforeEach
    void setUp() {
        com.walletradar.common.StablecoinRegistry registry = new com.walletradar.common.StablecoinRegistry();
        StablecoinResolver stablecoin = new StablecoinResolver(registry);
        com.walletradar.pricing.config.PricingProperties props = new com.walletradar.pricing.config.PricingProperties();
        props.getContractToCoinGeckoId().put("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "weth");
        CoinGeckoHistoricalResolver coinGecko = new CoinGeckoHistoricalResolver(
                props,
                org.springframework.web.reactive.function.client.WebClient.builder(),
                new com.walletradar.common.RateLimiter(45));
        CounterpartPriceResolver counterpart = new CounterpartPriceResolver(stablecoin, coinGecko);
        SwapDerivedResolver swapDerived = new SwapDerivedResolver(counterpart);
        chain = new HistoricalPriceResolverChain(stablecoin, swapDerived, coinGecko);
    }

    @Test
    @DisplayName("stablecoin is resolved first and returns $1")
    void stablecoinFirst() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = chain.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceUsd()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo(BigDecimal.ONE));
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("unknown token with no CoinGecko id returns UNKNOWN")
    void unknownTokenReturnsUnknown() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0x0000000000000000000000000000000000000001");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = chain.resolve(req);

        assertThat(r.isUnknown()).isTrue();
    }
}
