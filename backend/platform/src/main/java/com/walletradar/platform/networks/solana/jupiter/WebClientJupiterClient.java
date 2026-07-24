package com.walletradar.platform.networks.solana.jupiter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link JupiterClient} mirroring the Helius client resilience pattern
 * (bounded in-memory buffer, per-request timeout, exponential backoff on transient failures,
 * shared client-side throttle). Never throws: all failures resolve to empty results.
 */
@Slf4j
public class WebClientJupiterClient implements JupiterClient {

    /** 4 MB — Price v3 responses for a batch of ≤50 mints are small; token metadata is tiny. */
    private static final int MAX_IN_MEMORY_SIZE = 4 * 1024 * 1024;
    private static final long RETRY_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(500);
    private static final String API_KEY_HEADER = "x-api-key";

    private final WebClient webClient;
    private final JupiterProperties properties;
    private final JupiterRequestThrottle throttle;
    private final ObjectMapper objectMapper;

    public WebClientJupiterClient(WebClient.Builder builder,
                                  JupiterProperties properties,
                                  JupiterRequestThrottle throttle,
                                  ObjectMapper objectMapper) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
        this.webClient = builder.exchangeStrategies(strategies).build();
        this.properties = properties;
        this.throttle = throttle;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JupiterTokenMetadata> fetchTokenMetadata(String mint) {
        if (!properties.isEnabled() || mint == null || mint.isBlank()) {
            return Optional.empty();
        }
        String url = properties.resolvedTokenUrl(mint);
        String body = get(url);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            // Tokens v2 search returns an array of token objects; select the exact mint (field "id").
            // Defensive fallback: a single object (legacy v1 shape) is treated as the match.
            JsonNode match = selectTokenNode(root, mint.trim());
            if (match == null) {
                return Optional.empty();
            }
            String symbol = text(match, "symbol");
            Integer decimals = match.path("decimals").isIntegralNumber() ? match.path("decimals").asInt() : null;
            String name = text(match, "name");
            if (symbol == null && decimals == null && name == null) {
                return Optional.empty();
            }
            return Optional.of(new JupiterTokenMetadata(symbol, decimals, name));
        } catch (Exception ex) {
            log.debug("Jupiter token metadata parse failed for mint {}: {}", mint, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Picks the token node whose {@code id} equals the mint from a v2 search array, else the object itself. */
    private static JsonNode selectTokenNode(JsonNode root, String mint) {
        if (root.isArray()) {
            for (JsonNode element : root) {
                if (mint.equals(element.path("id").asText(null))) {
                    return element;
                }
            }
            return null;
        }
        return root.isObject() ? root : null;
    }

    @Override
    public Map<String, BigDecimal> fetchPrices(List<String> mints) {
        if (!properties.isEnabled() || mints == null || mints.isEmpty()) {
            return Map.of();
        }
        String ids = String.join(",", mints);
        String url = UriComponentsBuilder.fromUriString(properties.resolvedPriceUrl())
                .queryParam("ids", ids)
                .build()
                .toUriString();
        String body = get(url);
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            // v3 shape: { "<mint>": { "usdPrice": <number>, ... } }
            // v2 shape (defensive): { "data": { "<mint>": { "price": <number>, ... } } }
            JsonNode container = root.has("data") && root.path("data").isObject() ? root.path("data") : root;
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            container.fields().forEachRemaining(entry -> {
                BigDecimal price = extractPrice(entry.getValue());
                if (price != null && price.signum() > 0) {
                    result.put(entry.getKey(), price);
                }
            });
            return result;
        } catch (Exception ex) {
            log.debug("Jupiter price parse failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static BigDecimal extractPrice(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.hasNonNull("usdPrice") ? node.path("usdPrice") : node.path("price");
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String raw = value.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String get(String url) {
        throttle.acquire();
        try {
            WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(url);
            if (properties.usesApiKey()) {
                spec = spec.header(API_KEY_HEADER, properties.getApiKey().trim());
            }
            return spec
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getTimeoutMs())))
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientJupiterClient::isRetryable)
                            .transientErrors(true))
                    .block();
        } catch (Exception ex) {
            log.debug("Jupiter GET {} failed: {}", url, ex.getMessage());
            return null;
        }
    }

    /** Retry only on transient failures: HTTP 429 / 5xx, request I/O errors, and read timeouts. */
    private static boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        if (throwable instanceof WebClientRequestException
                || throwable instanceof TimeoutException
                || throwable instanceof IOException) {
            return true;
        }
        Throwable cause = throwable.getCause();
        return cause instanceof TimeoutException || cause instanceof IOException;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            String txt = value.asText().trim();
            return txt.isBlank() ? null : txt;
        }
        return null;
    }
}
