package com.walletradar.pricing;

import com.walletradar.pricing.config.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpotPriceResolverTest {

    private SpotPriceResolver resolver;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties();
        props.setContractToCoinGeckoId(Map.of("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "weth"));
        resolver = new SpotPriceResolver(props, org.springframework.web.reactive.function.client.WebClient.builder());
    }

    @Test
    @DisplayName("parseUsdPrice extracts usd for coin id")
    void parseUsdPrice() {
        String json = "{\"weth\": {\"usd\": 3500.25}}";
        Optional<java.math.BigDecimal> price = SpotPriceResolver.parseUsdPrice(json, "weth");
        assertThat(price).isPresent();
        assertThat(price.get()).isEqualByComparingTo("3500.25");
    }

    @Test
    @DisplayName("resolve returns empty for unknown contract")
    void unknownContractReturnsEmpty() {
        assertThat(resolver.resolve("0x0000000000000000000000000000000000000001")).isEmpty();
    }

    @Test
    @DisplayName("resolve returns empty for null contract")
    void nullContractReturnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
