package com.walletradar.pricing.resolver.external.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.pricing.application.PricingProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Bybit spot kline client for one-minute historical price buckets.
 */
@Component
public class BybitKlineClient {

    private static final String CATEGORY = "spot";
    private static final String INTERVAL = "1";

    private final PricingProperties pricingProperties;
    private final WebClient webClient;
    private final Set<String> unsupportedSymbols = ConcurrentHashMap.newKeySet();

    public BybitKlineClient(WebClient.Builder webClientBuilder, PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
        this.webClient = webClientBuilder
                .baseUrl(pricingProperties.getExternal().getBybit().getBaseUrl())
                .build();
    }

    public Optional<BybitKline> fetchKline(String symbol, Instant occurredAt) {
        if (unsupportedSymbols.contains(symbol)) {
            return Optional.empty();
        }
        Instant bucketStart = occurredAt.truncatedTo(ChronoUnit.MINUTES);
        long startTime = bucketStart.toEpochMilli();
        long endTime = bucketStart.plusSeconds(60).toEpochMilli();
        try {
            JsonNode body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v5/market/kline")
                            .queryParam("category", CATEGORY)
                            .queryParam("symbol", symbol)
                            .queryParam("interval", INTERVAL)
                            .queryParam("start", startTime)
                            .queryParam("end", endTime)
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(pricingProperties.getExternal().getRequestTimeoutMs()));
            if (body == null || body.path("retCode").asInt(-1) != 0) {
                return Optional.empty();
            }
            JsonNode list = body.path("result").path("list");
            if (!list.isArray() || list.isEmpty() || !list.get(0).isArray()) {
                return Optional.empty();
            }
            JsonNode kline = list.get(0);
            return Optional.of(new BybitKline(
                    symbol,
                    Instant.ofEpochMilli(kline.get(0).asLong()),
                    new BigDecimal(kline.get(1).asText())
            ));
        } catch (WebClientResponseException error) {
            if (error.getStatusCode().value() == 400 || error.getStatusCode().value() == 404) {
                unsupportedSymbols.add(symbol);
                return Optional.empty();
            }
            throw error;
        }
    }

    public record BybitKline(
            String symbol,
            Instant openTime,
            BigDecimal openPriceUsd
    ) {
    }
}
