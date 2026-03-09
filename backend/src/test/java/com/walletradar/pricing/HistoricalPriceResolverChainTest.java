package com.walletradar.pricing;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.resolver.CoinGeckoHistoricalResolver;
import com.walletradar.pricing.resolver.CounterpartPriceResolver;
import com.walletradar.pricing.resolver.NativeAssetResolver;
import com.walletradar.pricing.resolver.StablecoinResolver;
import com.walletradar.pricing.resolver.SwapDerivedResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

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
        var contractResolver = new com.walletradar.pricing.resolver.ConfigOverrideContractResolver(props);
        CoinGeckoHistoricalResolver coinGecko = new CoinGeckoHistoricalResolver(
                props,
                org.springframework.web.reactive.function.client.WebClient.builder(),
                new com.walletradar.common.RateLimiter(45),
                contractResolver,
                new DefaultResourceLoader());
        NativeAssetResolver nativeAssetResolver = new NativeAssetResolver(coinGecko);
        CounterpartPriceResolver counterpart = new CounterpartPriceResolver(stablecoin, coinGecko);
        SwapDerivedResolver swapDerived = new SwapDerivedResolver(counterpart);
        chain = new HistoricalPriceResolverChain(stablecoin, nativeAssetResolver, swapDerived, coinGecko);
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
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("audited EURC contract resolves through stablecoin path")
    void eurcStablecoin() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xc891eb4cbdeff6e073e859e987815ed1505c2acd");
        req.setNetworkId(NetworkId.AVALANCHE);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = chain.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceUsd()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo(BigDecimal.ONE));
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("audited stable aliases resolve through stablecoin path")
    void auditedStableAliasesResolveToOneUsd() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0x9151434b16b9763660705744891fa906f660ecc5");
        req.setNetworkId(NetworkId.UNICHAIN);
        req.setBlockTimestamp(Instant.now());

        PriceResolutionResult r = chain.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceUsd()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo(BigDecimal.ONE));
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.STABLECOIN);
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
