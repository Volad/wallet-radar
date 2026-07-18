package com.walletradar.application.pricing.latest;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.pricing.application.PricingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bybit spot tickers bulk client.
 * Issues a single GET /v5/market/tickers?category=spot call that returns ALL spot pairs.
 */
@Component
public class BybitTickerClient {

    private static final Logger log = LoggerFactory.getLogger(BybitTickerClient.class);

    private final WebClient webClient;
    private final PricingProperties pricingProperties;

    public BybitTickerClient(WebClient.Builder webClientBuilder, PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
        this.webClient = webClientBuilder
                .baseUrl(pricingProperties.getExternal().getBybit().getBaseUrl())
                .build();
    }

    /**
     * Fetches all Bybit spot tickers in a single HTTP call.
     *
     * @return list of raw ticker rows; empty on any error
     */
    public List<BybitTicker> fetchAllSpotTickers() {
        try {
            JsonNode body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v5/market/tickers")
                            .queryParam("category", "spot")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(pricingProperties.getExternal().getRequestTimeoutMs()));

            if (body == null || body.path("retCode").asInt(-1) != 0) {
                log.warn("Bybit tickers returned non-zero retCode: {}", body);
                return List.of();
            }

            JsonNode list = body.path("result").path("list");
            if (!list.isArray()) {
                log.warn("Bybit tickers response missing result.list array");
                return List.of();
            }

            Instant fetchedAt = Instant.now();
            List<BybitTicker> tickers = new ArrayList<>(list.size());
            for (JsonNode node : list) {
                String symbol = node.path("symbol").asText(null);
                String lastPriceStr = node.path("lastPrice").asText(null);
                if (symbol == null || symbol.isBlank() || lastPriceStr == null || lastPriceStr.isBlank()) {
                    continue;
                }
                try {
                    BigDecimal lastPrice = new BigDecimal(lastPriceStr);
                    if (lastPrice.signum() <= 0) continue;
                    tickers.add(new BybitTicker(symbol, lastPrice, fetchedAt));
                } catch (NumberFormatException ignored) {
                    // skip unparseable price rows
                }
            }
            return tickers;
        } catch (Exception ex) {
            log.warn("Bybit fetchAllSpotTickers failed: {}", ex.getMessage());
            return List.of();
        }
    }

    public record BybitTicker(
            /** Native Bybit ticker symbol, e.g. "ETHUSDT". */
            String symbol,
            BigDecimal lastPrice,
            Instant fetchedAt
    ) {
    }
}
