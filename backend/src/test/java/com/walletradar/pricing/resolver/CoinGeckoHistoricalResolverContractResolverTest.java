package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import com.walletradar.pricing.config.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CoinGeckoHistoricalResolver uses ContractToCoinGeckoIdResolver for coin ID lookup.
 */
class CoinGeckoHistoricalResolverContractResolverTest {

    private CoinGeckoHistoricalResolver resolver;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://api.coingecko.com/api/v3");
        ContractToCoinGeckoIdResolver contractResolver = (contract, networkId) ->
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2".equalsIgnoreCase(contract)
                        ? Optional.of("weth")
                        : Optional.empty();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body("{\"market_data\":{\"current_price\":{\"usd\":3500.25}}}")
                        .build()));
        resolver = new CoinGeckoHistoricalResolver(
                props,
                webClientBuilder,
                new com.walletradar.common.RateLimiter(100),
                contractResolver);
    }

    @Test
    @DisplayName("resolves price when contract resolver returns coinId")
    void resolvesWhenContractResolverReturnsCoinId() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2024-06-01T12:00:00Z"));

        PriceResolutionResult result = resolver.resolve(req);

        assertThat(result.isUnknown()).isFalse();
        assertThat(result.getPriceUsd()).isPresent();
    }

    @Test
    @DisplayName("returns UNKNOWN when contract resolver returns empty")
    void returnsUnknownWhenContractResolverReturnsEmpty() {
        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0x0000000000000000000000000000000000000001");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2024-06-01T12:00:00Z"));

        PriceResolutionResult result = resolver.resolve(req);

        assertThat(result.isUnknown()).isTrue();
    }
}
