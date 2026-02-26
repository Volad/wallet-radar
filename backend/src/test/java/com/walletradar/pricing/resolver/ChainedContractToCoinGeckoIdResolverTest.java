package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChainedContractToCoinGeckoIdResolverTest {

    private ChainedContractToCoinGeckoIdResolver chain;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties();
        props.setContractToCoinGeckoId(Map.of("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "weth"));
        props.getContractMapping().setEnabled(true);
        ConfigOverrideContractResolver configOverride = new ConfigOverrideContractResolver(props);
        com.github.benmanes.caffeine.cache.Cache<String, java.util.Map<String, String>> cache =
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(1).build();
        cache.put("coins-list", java.util.Map.of());
        CoinsListBulkResolver coinsList = new CoinsListBulkResolver(props, org.springframework.web.reactive.function.client.WebClient.builder(), cache);
        chain = new ChainedContractToCoinGeckoIdResolver(configOverride, coinsList);
    }

    @Test
    @DisplayName("config override takes precedence over coins list")
    void configOverrideTakesPrecedence() {
        Optional<String> result = chain.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", NetworkId.ETHEREUM);
        assertThat(result).hasValue("weth");
    }

    @Test
    @DisplayName("returns empty for unknown contract when coins list not fetched")
    void unknownContractReturnsEmpty() {
        Optional<String> result = chain.resolve("0x0000000000000000000000000000000000000001", NetworkId.ETHEREUM);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty for null contract")
    void nullContractReturnsEmpty() {
        assertThat(chain.resolve(null, NetworkId.ETHEREUM)).isEmpty();
    }
}
