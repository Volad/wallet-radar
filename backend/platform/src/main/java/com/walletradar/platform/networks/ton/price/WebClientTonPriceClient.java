package com.walletradar.platform.networks.ton.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.ton.TonAddressCanonicalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link TonPriceClient} for the STON.fi {@code GET /v1/assets} feed, mirroring the
 * Jupiter/Helius client resilience pattern (bounded in-memory buffer, per-request timeout,
 * exponential backoff on transient failures, shared client-side throttle). Never throws: all
 * failures resolve to an empty map.
 */
@Slf4j
public class WebClientTonPriceClient implements TonPriceClient {

    /** 16 MB — the full STON.fi asset list is a few thousand entries. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;
    private static final long RETRY_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(500);
    private static final String ASSETS_PATH = "/assets";
    private static final Pattern RAW_ADDRESS = Pattern.compile("^-?\\d+:[0-9a-fA-F]{64}$");

    private final WebClient webClient;
    private final TonPriceProperties properties;
    private final TonPriceRequestThrottle throttle;
    private final ObjectMapper objectMapper;

    public WebClientTonPriceClient(WebClient.Builder builder,
                                   TonPriceProperties properties,
                                   TonPriceRequestThrottle throttle,
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
    public Map<String, BigDecimal> fetchAllPrices() {
        if (!properties.isEnabled()) {
            return Map.of();
        }
        String url = baseUrl() + ASSETS_PATH;
        String body = get(url);
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.has("asset_list") ? root.path("asset_list") : root;
            if (!list.isArray()) {
                return Map.of();
            }
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (JsonNode asset : list) {
                String contract = text(asset, "contract_address");
                if (contract == null) {
                    continue;
                }
                BigDecimal price = extractPrice(asset);
                if (price == null || price.signum() <= 0) {
                    continue;
                }
                String key = rawKey(contract);
                if (key != null) {
                    result.putIfAbsent(key, price);
                }
            }
            log.debug("STON.fi price client: parsed {} priced jettons from {} assets", result.size(), list.size());
            return result;
        } catch (Exception ex) {
            log.debug("STON.fi price parse failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Canonical lookup key for a jetton master: the raw {@code workchain:hex} form (lowercase), which
     * both STON.fi ({@code EQ…} friendly) and on-chain balance rows ({@code 0:hex}) resolve to.
     */
    static String rawKey(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        for (String candidate : TonAddressCanonicalizer.lookupKeys(address)) {
            if (RAW_ADDRESS.matcher(candidate).matches()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        String trimmed = address.trim();
        return RAW_ADDRESS.matcher(trimmed).matches() ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    /** Reads the USD price defensively across STON.fi field-name variants. */
    private static BigDecimal extractPrice(JsonNode asset) {
        for (String field : new String[] {"dex_price_usd", "dex_usd_price", "price_usd", "usd_price",
                "third_party_price_usd", "third_party_usd_price"}) {
            BigDecimal price = readDecimal(asset.path(field));
            if (price != null) {
                return price;
            }
        }
        return null;
    }

    private static BigDecimal readDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String raw = node.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String baseUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        return base.replaceAll("/+$", "");
    }

    private String get(String url) {
        throttle.acquire();
        try {
            return webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getTimeoutMs())))
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientTonPriceClient::isRetryable)
                            .transientErrors(true))
                    .block();
        } catch (Exception ex) {
            log.debug("STON.fi GET {} failed: {}", url, ex.getMessage());
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
