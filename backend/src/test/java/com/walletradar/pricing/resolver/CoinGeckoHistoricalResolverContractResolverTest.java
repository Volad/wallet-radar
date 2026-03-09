package com.walletradar.pricing.resolver;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.HistoricalPriceRequest;
import com.walletradar.pricing.PriceResolutionResult;
import com.walletradar.pricing.config.PricingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CoinGeckoHistoricalResolver uses ContractToCoinGeckoIdResolver for live API
 * and bundled historical fallback for old demo/public dates.
 */
class CoinGeckoHistoricalResolverContractResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("resolves price from CoinGecko API when contract resolver returns coinId and pro access is configured")
    void resolvesWhenContractResolverReturnsCoinId() {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> apiKeyValue = new AtomicReference<>();

        PricingProperties props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://pro-api.coingecko.com/api/v3");
        props.setCoingeckoApiKey("cg-pro-key");
        props.setCoingeckoApiKeyHeader("x-cg-pro-api-key");

        ContractToCoinGeckoIdResolver contractResolver = (contract, networkId) ->
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2".equalsIgnoreCase(contract)
                        ? Optional.of("weth")
                        : Optional.empty();

        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(req -> {
                    apiKeyHeader.set("x-cg-pro-api-key");
                    apiKeyValue.set(req.headers().getFirst("x-cg-pro-api-key"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"market_data\":{\"current_price\":{\"usd\":3500.25}}}")
                            .build());
                });

        CoinGeckoHistoricalResolver resolver = new CoinGeckoHistoricalResolver(
                props,
                webClientBuilder,
                new com.walletradar.common.RateLimiter(100),
                contractResolver,
                new DefaultResourceLoader(),
                Clock.fixed(Instant.parse("2026-03-07T00:00:00Z"), ZoneOffset.UTC));

        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2024-06-01T12:00:00Z"));

        PriceResolutionResult result = resolver.resolve(req);

        assertThat(result.isUnknown()).isFalse();
        assertThat(result.getPriceUsd()).hasValueSatisfying(price ->
                assertThat(price.toPlainString()).isEqualTo("3500.250000000000000000"));
        assertThat(apiKeyHeader.get()).isEqualTo("x-cg-pro-api-key");
        assertThat(apiKeyValue.get()).isEqualTo("cg-pro-key");
    }

    @Test
    @DisplayName("uses bundled fallback for demo/public dates older than retention window")
    void usesBundledFallbackForOldDemoDate() throws Exception {
        Path bundledFile = tempDir.resolve("historical-fallback.json");
        Files.writeString(bundledFile, """
                [
                  {
                    "assetSymbol": "ETH",
                    "network": "ETHEREUM",
                    "assetContract": "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    "dateTime": "2025-02-05T08:24:59.000Z",
                    "coinId": "ethereum",
                    "priceUsd": 2765.6860601377734,
                    "priceSource": "DEFILLAMA_CONTRACT",
                    "lookupStatus": "RESOLVED",
                    "note": "historical-contract-or-native-lookup"
                  }
                ]
                """);

        PricingProperties props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://api.coingecko.com/api/v3");
        props.setCoingeckoApiKey("cg-demo-key");
        props.setCoingeckoApiKeyHeader("x-cg-demo-api-key");
        props.getHistoricalFallback().setResourcePath(bundledFile.toUri().toString());

        AtomicInteger apiCalls = new AtomicInteger();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(req -> {
                    apiCalls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"market_data\":{\"current_price\":{\"usd\":9999.99}}}")
                            .build());
                });

        CoinGeckoHistoricalResolver resolver = new CoinGeckoHistoricalResolver(
                props,
                webClientBuilder,
                new com.walletradar.common.RateLimiter(100),
                (contract, networkId) -> Optional.empty(),
                new DefaultResourceLoader(),
                Clock.fixed(Instant.parse("2026-03-07T00:00:00Z"), ZoneOffset.UTC));

        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2025-02-05T12:00:00Z"));

        PriceResolutionResult result = resolver.resolve(req);

        assertThat(result.isUnknown()).isFalse();
        assertThat(result.getPriceUsd()).hasValueSatisfying(price ->
                assertThat(price.toPlainString()).isEqualTo("2765.686060137773400000"));
        assertThat(apiCalls.get()).isZero();
    }

    @Test
    @DisplayName("returns UNKNOWN when contract resolver returns empty for recent date")
    void returnsUnknownWhenContractResolverReturnsEmpty() {
        PricingProperties props = new PricingProperties();
        props.setCoingeckoBaseUrl("https://api.coingecko.com/api/v3");
        props.setCoingeckoApiKey("cg-demo-key");
        props.setCoingeckoApiKeyHeader("x-cg-demo-api-key");

        CoinGeckoHistoricalResolver resolver = new CoinGeckoHistoricalResolver(
                props,
                WebClient.builder(),
                new com.walletradar.common.RateLimiter(100),
                (contract, networkId) -> Optional.empty(),
                new DefaultResourceLoader(),
                Clock.fixed(Instant.parse("2026-03-07T00:00:00Z"), ZoneOffset.UTC));

        HistoricalPriceRequest req = new HistoricalPriceRequest();
        req.setAssetContract("0x0000000000000000000000000000000000000001");
        req.setNetworkId(NetworkId.ETHEREUM);
        req.setBlockTimestamp(Instant.parse("2026-03-01T12:00:00Z"));

        PriceResolutionResult result = resolver.resolve(req);

        assertThat(result.isUnknown()).isTrue();
    }
}
