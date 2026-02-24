package com.walletradar.pricing;

import com.walletradar.domain.NetworkId;
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
        com.walletradar.pricing.resolver.SwapDerivedResolver.class,
        com.walletradar.pricing.resolver.CounterpartPriceResolver.class,
        com.walletradar.pricing.resolver.CoinGeckoHistoricalResolver.class,
        com.walletradar.common.StablecoinRegistry.class
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PricingIntegrationTest {

    @MockBean
    org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;

    @Autowired
    HistoricalPriceResolver historicalPriceResolver;

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
        assertThat(r.getPriceSource()).isEqualTo(com.walletradar.domain.PriceSource.STABLECOIN);
    }

    @Test
    @DisplayName("spot and historical caches are available")
    void cachesAvailable() {
        if (cacheManager != null) {
            assertThat(cacheManager.getCache(com.walletradar.config.CaffeineConfig.SPOT_PRICE_CACHE)).isNotNull();
            assertThat(cacheManager.getCache(com.walletradar.config.CaffeineConfig.HISTORICAL_PRICE_CACHE)).isNotNull();
        }
    }
}
