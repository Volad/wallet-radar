package com.walletradar.pricing.resolver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import org.springframework.web.reactive.function.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CoinsListBulkResolverTest {

    private static final String COINS_LIST_JSON = """
            [
              {"id":"ethereum","symbol":"eth","name":"Ethereum","platforms":{"ethereum":"0x"}},
              {"id":"weth","symbol":"weth","name":"WETH","platforms":{"ethereum":"0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2","arbitrum-one":"0x82af49447d8a07e3bd95bd0d56f35241523fbab1"}},
              {"id":"usd-coin","symbol":"usdc","name":"USD Coin","platforms":{"ethereum":"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48","arbitrum-one":"0xaf88d065e77c8cc2239327c5edb3a432268e5831"}}
            ]""";

    private Cache<String, Map<String, String>> cache;
    private PricingProperties props;

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(1)
                .build();
        props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://api.coingecko.com/api/v3");
        props.getContractMapping().setEnabled(true);
    }

    @Test
    @DisplayName("parseAndBuildIndex builds platform:contract lookup")
    void parseAndBuildIndex() {
        Map<String, String> index = CoinsListBulkResolver.parseAndBuildIndex(COINS_LIST_JSON);
        assertThat(index.get("ethereum:0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")).isEqualTo("weth");
        assertThat(index.get("arbitrum-one:0x82af49447d8a07e3bd95bd0d56f35241523fbab1")).isEqualTo("weth");
        assertThat(index.get("ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")).isEqualTo("usd-coin");
        assertThat(index.get(":0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")).isEqualTo("weth");
    }

    @Test
    @DisplayName("resolve returns coinId when cache is pre-populated")
    void resolveWithPrePopulatedCache() {
        cache.put("coins-list", CoinsListBulkResolver.parseAndBuildIndex(COINS_LIST_JSON));
        CoinsListBulkResolver resolver = new CoinsListBulkResolver(props, WebClient.builder(), cache);

        assertThat(resolver.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", NetworkId.ETHEREUM))
                .hasValue("weth");
        assertThat(resolver.resolve("0x82af49447d8a07e3bd95bd0d56f35241523fbab1", NetworkId.ARBITRUM))
                .hasValue("weth");
        assertThat(resolver.resolve("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", NetworkId.ETHEREUM))
                .hasValue("usd-coin");
    }

    @Test
    @DisplayName("resolve uses contract-only fallback when networkId has no platform")
    void resolveContractOnlyFallback() {
        cache.put("coins-list", CoinsListBulkResolver.parseAndBuildIndex(COINS_LIST_JSON));
        CoinsListBulkResolver resolver = new CoinsListBulkResolver(props, WebClient.builder(), cache);
        assertThat(resolver.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", null))
                .hasValue("weth");
    }

    @Test
    @DisplayName("returns empty when contract-mapping disabled")
    void disabledReturnsEmpty() {
        props.getContractMapping().setEnabled(false);
        CoinsListBulkResolver resolver = new CoinsListBulkResolver(props, WebClient.builder(), cache);
        assertThat(resolver.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", NetworkId.ETHEREUM))
                .isEmpty();
    }

    @Test
    @DisplayName("returns empty for null or blank contract")
    void nullOrBlankReturnsEmpty() {
        cache.put("coins-list", CoinsListBulkResolver.parseAndBuildIndex(COINS_LIST_JSON));
        CoinsListBulkResolver resolver = new CoinsListBulkResolver(props, WebClient.builder(), cache);
        assertThat(resolver.resolve(null, NetworkId.ETHEREUM)).isEmpty();
        assertThat(resolver.resolve("", NetworkId.ETHEREUM)).isEmpty();
    }
}
