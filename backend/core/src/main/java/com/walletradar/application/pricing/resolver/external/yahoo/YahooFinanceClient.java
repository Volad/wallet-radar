package com.walletradar.application.pricing.resolver.external.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Unofficial Yahoo Finance v8 chart client for US equity prices.
 *
 * <p>Used as a fallback for equity symbols not supported by Dzengi's kline API
 * (e.g. GOOGL, NFLX, PYPL, SNAP, BABA). No API key required.
 *
 * <p>Fetches a 3-day daily window around {@code occurredAt} and returns the closest
 * closing price. Results are cached by {@link
 * com.walletradar.application.pricing.resolver.external.PriceExternalSourceOrchestrator}
 * so this client is called at most once per (symbol, day) pair.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class YahooFinanceClient {

    private static final String BASE_URL = "https://query1.finance.yahoo.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long WINDOW_BEFORE_SECS = 4 * 24 * 3600L;
    private static final long WINDOW_AFTER_SECS = 24 * 3600L;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public record YahooQuote(BigDecimal usdPrice, Instant closeTime) {
    }

    /**
     * Fetches the daily closing price for {@code symbol} on or just before {@code occurredAt}.
     * Returns empty if the symbol is unknown or the API is unavailable.
     */
    public Optional<YahooQuote> fetchUsdClose(String symbol, Instant occurredAt) {
        if (symbol == null || symbol.isBlank() || occurredAt == null) {
            return Optional.empty();
        }
        long period2 = occurredAt.plusSeconds(WINDOW_AFTER_SECS).getEpochSecond();
        long period1 = occurredAt.minusSeconds(WINDOW_BEFORE_SECS).getEpochSecond();
        String path = String.format(
                "/v8/finance/chart/%s?interval=1d&period1=%d&period2=%d",
                symbol.trim().toUpperCase(java.util.Locale.ROOT), period1, period2
        );
        try {
            WebClient client = webClientBuilder.baseUrl(BASE_URL).build();
            Mono<JsonNode> request = client.get()
                    .uri(URI.create(BASE_URL + path))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(JsonNode.class);
                        }
                        return Mono.just(objectMapper.createObjectNode());
                    })
                    .timeout(TIMEOUT)
                    .onErrorResume(ex -> {
                        log.debug("YahooFinance request failed for {}: {}", symbol, ex.getMessage());
                        return Mono.just(objectMapper.createObjectNode());
                    });

            JsonNode body = com.walletradar.platform.networks.ReactorBlocking.block(request, TIMEOUT);
            return parseClosestClose(body, occurredAt);
        } catch (Exception ex) {
            log.debug("YahooFinance error for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<YahooQuote> parseClosestClose(JsonNode body, Instant occurredAt) {
        if (body == null || body.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return Optional.empty();
        }
        JsonNode series = result.get(0);
        JsonNode timestamps = series.path("timestamp");
        JsonNode closes = series.path("indicators").path("quote").path(0).path("close");
        if (!timestamps.isArray() || !closes.isArray() || timestamps.isEmpty()) {
            return Optional.empty();
        }

        // Find the bar whose close time is closest to and not after occurredAt + 1 day
        long targetEpoch = occurredAt.getEpochSecond();
        long bestTs = Long.MIN_VALUE;
        BigDecimal bestClose = null;

        for (int i = 0; i < timestamps.size(); i++) {
            long ts = timestamps.get(i).asLong();
            if (ts > targetEpoch + WINDOW_AFTER_SECS) {
                continue;
            }
            JsonNode closeNode = closes.path(i);
            if (closeNode.isMissingNode() || closeNode.isNull()) {
                continue;
            }
            BigDecimal close = parseDecimal(closeNode.asText());
            if (close == null || close.signum() <= 0) {
                continue;
            }
            if (ts > bestTs) {
                bestTs = ts;
                bestClose = close;
            }
        }

        if (bestClose == null) {
            return Optional.empty();
        }
        return Optional.of(new YahooQuote(bestClose, Instant.ofEpochSecond(bestTs)));
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
