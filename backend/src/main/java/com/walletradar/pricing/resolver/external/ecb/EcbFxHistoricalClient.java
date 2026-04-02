package com.walletradar.pricing.resolver.external.ecb;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.pricing.application.PricingProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal ECB daily EUR/USD historical FX client with business-day fallback.
 */
@Component
public class EcbFxHistoricalClient {

    private static final String SERIES_KEY = "EXR/D.USD.EUR.SP00.A";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PricingProperties pricingProperties;
    private final WebClient webClient;

    public EcbFxHistoricalClient(WebClient.Builder webClientBuilder, PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
        this.webClient = webClientBuilder
                .baseUrl(pricingProperties.getExternal().getEcb().getBaseUrl())
                .build();
    }

    public Optional<EcbFxQuote> fetchEurUsd(Instant occurredAt) {
        LocalDate endDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate startDate = endDate.minusDays(pricingProperties.getExternal().getEcb().getBackfillDays());
        try {
            JsonNode body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/service/data/" + SERIES_KEY)
                            .queryParam("startPeriod", DATE_FORMATTER.format(startDate))
                            .queryParam("endPeriod", DATE_FORMATTER.format(endDate))
                            .queryParam("format", "jsondata")
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(pricingProperties.getExternal().getRequestTimeoutMs()));
            return parseLatestQuote(body);
        } catch (WebClientResponseException error) {
            int code = error.getStatusCode().value();
            if (code == 400 || code == 404) {
                return Optional.empty();
            }
            throw error;
        }
    }

    private Optional<EcbFxQuote> parseLatestQuote(JsonNode body) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode datasets = body.path("dataSets");
        if (!datasets.isArray() || datasets.isEmpty()) {
            return Optional.empty();
        }
        JsonNode observations = datasets.get(0)
                .path("series")
                .path("0:0:0:0:0")
                .path("observations");
        JsonNode periods = body.path("structure")
                .path("dimensions")
                .path("observation")
                .path(0)
                .path("values");
        if (!observations.isObject() || !periods.isArray() || periods.isEmpty()) {
            return Optional.empty();
        }

        int latestIndex = -1;
        BigDecimal latestRate = null;
        Iterator<Map.Entry<String, JsonNode>> fields = observations.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            int observationIndex = Integer.parseInt(entry.getKey());
            JsonNode observation = entry.getValue();
            if (!observation.isArray() || observation.isEmpty() || observation.get(0) == null) {
                continue;
            }
            latestIndex = Math.max(latestIndex, observationIndex);
            if (latestIndex == observationIndex) {
                latestRate = observation.get(0).decimalValue();
            }
        }
        if (latestIndex < 0 || latestRate == null || latestIndex >= periods.size()) {
            return Optional.empty();
        }
        String date = periods.get(latestIndex).path("id").asText();
        if (date == null || date.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EcbFxQuote(
                LocalDate.parse(date, DATE_FORMATTER).atStartOfDay().toInstant(ZoneOffset.UTC),
                latestRate
        ));
    }

    public record EcbFxQuote(
            Instant pricedAt,
            BigDecimal usdPerEur
    ) {
    }
}
