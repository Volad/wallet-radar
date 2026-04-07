package com.walletradar.pricing.resolver.external.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.pricing.application.PricingProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Minimal Binance kline client for one-minute historical price buckets.
 */
@Component
public class BinanceKlineClient {

    private static final String INTERVAL = "1m";

    private final PricingProperties pricingProperties;
    private final WebClient webClient;

    public BinanceKlineClient(WebClient.Builder webClientBuilder, PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
        this.webClient = webClientBuilder
                .baseUrl(pricingProperties.getExternal().getBinance().getBaseUrl())
                .build();
    }

    public Optional<BinanceKline> fetchKline(String symbol, Instant occurredAt) {
        Instant bucketStart = occurredAt.truncatedTo(ChronoUnit.MINUTES);
        long startTime = bucketStart.toEpochMilli();
        long endTime = bucketStart.plusSeconds(60).toEpochMilli();
        try {
            JsonNode body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v3/klines")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", INTERVAL)
                            .queryParam("startTime", startTime)
                            .queryParam("endTime", endTime)
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(pricingProperties.getExternal().getRequestTimeoutMs()));
            if (body == null || !body.isArray() || body.isEmpty() || !body.get(0).isArray()) {
                return Optional.empty();
            }
            JsonNode kline = body.get(0);
            return Optional.of(new BinanceKline(
                    symbol,
                    Instant.ofEpochMilli(kline.get(0).asLong()),
                    new BigDecimal(kline.get(1).asText())
            ));
        } catch (WebClientResponseException error) {
            HttpStatusCode statusCode = error.getStatusCode();
            if (statusCode.value() == 400 || statusCode.value() == 404) {
                return Optional.empty();
            }
            throw error;
        }
    }

    public record BinanceKline(
            String symbol,
            Instant openTime,
            BigDecimal openPriceUsd
    ) {
    }
}
