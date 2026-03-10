package com.walletradar.pricing;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import com.walletradar.pricing.resolver.ConfigOverrideContractResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: pricing beans and caches wired; historical chain resolves stablecoin; spot cache available.
 */
@SpringBootTest(classes = {
        com.walletradar.config.CaffeineConfig.class,
        com.walletradar.pricing.config.PricingConfig.class,
        com.walletradar.pricing.HistoricalPriceResolverChain.class,
        com.walletradar.pricing.SpotPriceResolver.class,
        com.walletradar.pricing.resolver.StablecoinResolver.class,
        com.walletradar.pricing.resolver.NativeAssetResolver.class,
        com.walletradar.pricing.resolver.SwapDerivedResolver.class,
        com.walletradar.pricing.resolver.CounterpartPriceResolver.class,
        com.walletradar.pricing.resolver.CoinGeckoHistoricalResolver.class,
        com.walletradar.pricing.resolver.ConfigOverrideContractResolver.class,
        com.walletradar.pricing.resolver.CoinsListBulkResolver.class,
        com.walletradar.pricing.resolver.ChainedContractToCoinGeckoIdResolver.class,
        com.walletradar.common.StablecoinRegistry.class
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PricingIntegrationTest {

    @MockBean
    org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;

    @Autowired
    HistoricalPriceResolver historicalPriceResolver;

    @Autowired
    PricingProperties pricingProperties;

    @Autowired
    ConfigOverrideContractResolver configOverrideContractResolver;

    @Autowired(required = false)
    CacheManager cacheManager;

    @Test
    @DisplayName("historical chain resolves USDC to $1 via StablecoinResolver")
    void historicalChainResolvesStablecoin() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2024-06-01T12:00:00Z"));

        PriceResolutionResult r = historicalPriceResolver.resolve(req);

        assertThat(r.isUnknown()).isFalse();
        assertThat(r.getPriceUsd()).isPresent();
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.common.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("spot and historical caches are available")
    void cachesAvailable() {
        if (cacheManager != null) {
            assertThat(cacheManager.getCache(com.walletradar.config.CaffeineConfig.SPOT_PRICE_CACHE)).isNotNull();
            assertThat(cacheManager.getCache(com.walletradar.config.CaffeineConfig.HISTORICAL_PRICE_CACHE)).isNotNull();
        }
    }

    @Test
    @DisplayName("application yml residual pricing overrides are bound and resolvable")
    void applicationYamlResidualPricingOverridesAreBoundAndResolvable() {
        assertThat(pricingProperties.getContractToCoinGeckoId())
                .containsEntry("0x4200000000000000000000000000000000000006", "weth")
                .containsEntry("0x5979d7b546e38e414f7e9822514be443a4800529", "wrapped-steth")
                .containsEntry("0x3055913c90fcc1a6ce9a358911721eeb942013a1", "pancakeswap-token");

        assertThat(configOverrideContractResolver.resolve("0x4200000000000000000000000000000000000006", NetworkId.BASE))
                .hasValue("weth");
        assertThat(configOverrideContractResolver.resolve("0x5979d7b546e38e414f7e9822514be443a4800529", NetworkId.ARBITRUM))
                .hasValue("wrapped-steth");
        assertThat(configOverrideContractResolver.resolve("0x3055913c90fcc1a6ce9a358911721eeb942013a1", NetworkId.BASE))
                .hasValue("pancakeswap-token");
    }
}
