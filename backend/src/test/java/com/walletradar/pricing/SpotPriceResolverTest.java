package com.walletradar.pricing;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import com.walletradar.pricing.resolver.ConfigOverrideContractResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpotPriceResolverTest {

    private SpotPriceResolver resolver;
    private AtomicReference<String> apiKeyValue;

    @BeforeEach
    void setUp() {
        apiKeyValue = new AtomicReference<>();
        PricingProperties props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://api.coingecko.com/api/v3");
        props.setCoingeckoApiKey("cg-demo-key");
        props.setCoingeckoApiKeyHeader("x-cg-demo-api-key");
        props.setContractToCoinGeckoId(Map.of("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "weth"));
        var contractResolver = new ConfigOverrideContractResolver(props);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(req -> {
                    apiKeyValue.set(req.headers().getFirst("x-cg-demo-api-key"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"weth\": {\"usd\": 3500.25}}")
                            .build());
                });
        resolver = new SpotPriceResolver(props, webClientBuilder, contractResolver);
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
        assertThat(resolver.resolve("0x0000000000000000000000000000000000000001", NetworkId.ETHEREUM)).isEmpty();
        assertThat(resolver.resolve("0x0000000000000000000000000000000000000001")).isEmpty();
    }

    @Test
    @DisplayName("resolve returns empty for null contract")
    void nullContractReturnsEmpty() {
        assertThat(resolver.resolve(null, NetworkId.ETHEREUM)).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("resolve(contract) delegates to resolve(contract, null)")
    void resolveWithoutNetworkIdUsesConfigOverride() {
        assertThat(resolver.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"))
                .isPresent();
        assertThat(apiKeyValue.get()).isEqualTo("cg-demo-key");
    }
}
