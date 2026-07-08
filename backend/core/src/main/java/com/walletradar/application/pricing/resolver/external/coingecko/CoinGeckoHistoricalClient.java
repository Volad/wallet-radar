package com.walletradar.application.pricing.resolver.external.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.pricing.application.PricingProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal CoinGecko historical client using the bounded daily history endpoint.
 */
@Component
public class CoinGeckoHistoricalClient {

    private static final DateTimeFormatter HISTORY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final PricingProperties pricingProperties;
    private final WebClient webClient;

    public CoinGeckoHistoricalClient(WebClient.Builder webClientBuilder, PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
        this.webClient = webClientBuilder
                .baseUrl(pricingProperties.getExternal().getCoinGecko().getBaseUrl())
                .build();
    }

    public Optional<CoinGeckoHistory> fetchHistory(String coinId, Instant occurredAt) {
        String date = HISTORY_DATE_FORMATTER.format(occurredAt);
        try {
            JsonNode body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/coins/{id}/history")
                            .queryParam("date", date)
                            .queryParam("localization", "false")
                            .build(coinId))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(pricingProperties.getExternal().getRequestTimeoutMs()));
            if (body == null) {
                return Optional.empty();
            }
            JsonNode usdNode = body.path("market_data").path("current_price").path("usd");
            if (usdNode.isMissingNode() || usdNode.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new CoinGeckoHistory(
                    coinId,
                    occurredAt,
                    usdNode.decimalValue()
            ));
        } catch (WebClientResponseException error) {
            HttpStatusCode statusCode = error.getStatusCode();
            if (statusCode.value() == 400
                    || statusCode.value() == 401
                    || statusCode.value() == 403
                    || statusCode.value() == 404) {
                return Optional.empty();
            }
            throw error;
        }
    }

    public record CoinGeckoHistory(
            String coinId,
            Instant pricedAt,
            BigDecimal priceUsd
    ) {
    }
}
