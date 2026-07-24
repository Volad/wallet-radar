package com.walletradar.platform.networks.ton.metadata;

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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link TonMetadataClient} for the TON Center v3
 * {@code GET /jetton/masters?address=...} feed, mirroring the WS-6 TON price-client resilience
 * pattern (bounded in-memory buffer, per-request timeout, exponential backoff on transient failures,
 * shared client-side throttle). Never throws: all failures resolve to {@link Optional#empty()}.
 */
@Slf4j
public class WebClientTonMetadataClient implements TonMetadataClient {

    /** 4 MB — a single jetton-master document is tiny. */
    private static final int MAX_IN_MEMORY_SIZE = 4 * 1024 * 1024;
    private static final long RETRY_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(500);
    private static final String MASTERS_PATH = "/jetton/masters";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final WebClient webClient;
    private final TonMetadataProperties properties;
    private final TonMetadataRequestThrottle throttle;
    private final ObjectMapper objectMapper;

    public WebClientTonMetadataClient(WebClient.Builder builder,
                                      TonMetadataProperties properties,
                                      TonMetadataRequestThrottle throttle,
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
    public Optional<TonJettonMetadata> fetchJettonMetadata(String jettonMaster) {
        if (!properties.isEnabled() || jettonMaster == null || jettonMaster.isBlank()) {
            return Optional.empty();
        }
        String url = UriComponentsBuilder.fromUriString(baseUrl() + MASTERS_PATH)
                .queryParam("address", jettonMaster.trim())
                .queryParam("limit", 1)
                .build()
                .toUriString();
        String body = get(url);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode master = selectMaster(root);
            if (master == null) {
                return Optional.empty();
            }
            JsonNode content = master.path("jetton_content");
            String symbol = text(content, "symbol");
            Integer decimals = intValue(content.path("decimals"));

            // Off-chain / semi-chain jetton content: TON Center reports decimals but the human-facing
            // symbol/name live behind a content URI. Follow it (throttled) so long-tail jettons resolve
            // a real symbol instead of falling back to the raw jetton address.
            if (symbol == null) {
                OffChainContent offChain = fetchOffChainContent(contentUri(content, master));
                if (offChain != null) {
                    symbol = offChain.symbol();
                    if (decimals == null) {
                        decimals = offChain.decimals();
                    }
                }
            }

            if (symbol == null && decimals == null) {
                return Optional.empty();
            }
            return Optional.of(new TonJettonMetadata(symbol, decimals));
        } catch (Exception ex) {
            log.debug("TON jetton metadata parse failed for master {}: {}", jettonMaster, ex.getMessage());
            return Optional.empty();
        }
    }

    /** The off-chain content URI TON Center exposes for semi/off-chain jetton content, if any. */
    static String contentUri(JsonNode content, JsonNode master) {
        for (JsonNode node : new JsonNode[]{content, master}) {
            if (node == null || node.isMissingNode()) {
                continue;
            }
            for (String field : new String[]{"uri", "_uri", "content_uri"}) {
                String uri = text(node, field);
                if (uri != null) {
                    return uri;
                }
            }
        }
        return null;
    }

    /**
     * Fetches the off-chain jetton metadata JSON ({@code {symbol, name, decimals}}) from a content
     * URI. Uses the same throttle/timeout/retry envelope as the master call and never throws — an
     * unreachable or malformed document resolves to {@code null}.
     */
    private OffChainContent fetchOffChainContent(String uri) {
        String url = normalizeContentUri(uri);
        if (url == null) {
            return null;
        }
        String body = getContent(url);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return parseOffChainContent(objectMapper.readTree(body));
        } catch (Exception ex) {
            log.debug("TON off-chain jetton content parse failed for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    /** Parses an off-chain jetton metadata JSON document into {@code {symbol, decimals}}, or null. */
    static OffChainContent parseOffChainContent(JsonNode root) {
        if (root == null) {
            return null;
        }
        String symbol = text(root, "symbol");
        Integer decimals = intValue(root.path("decimals"));
        if (symbol == null && decimals == null) {
            return null;
        }
        return new OffChainContent(symbol, decimals);
    }

    /** Resolves an http(s) URL, mapping {@code ipfs://} to a public gateway; else {@code null}. */
    static String normalizeContentUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String trimmed = uri.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed;
        }
        if (lower.startsWith("ipfs://")) {
            return "https://ipfs.io/ipfs/" + trimmed.substring("ipfs://".length());
        }
        return null;
    }

    /** GET for an arbitrary content host — same resilience envelope, without the TON Center API key. */
    private String getContent(String url) {
        throttle.acquire();
        try {
            return webClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getTimeoutMs())))
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientTonMetadataClient::isRetryable)
                            .transientErrors(true))
                    .block();
        } catch (Exception ex) {
            log.debug("TON off-chain content GET {} failed: {}", url, ex.getMessage());
            return null;
        }
    }

    /** Minimal off-chain jetton metadata subset. */
    record OffChainContent(String symbol, Integer decimals) {
    }

    private static JsonNode selectMaster(JsonNode root) {
        JsonNode masters = root.path("jetton_masters");
        if (masters.isArray() && !masters.isEmpty()) {
            return masters.get(0);
        }
        return root.has("jetton_content") ? root : null;
    }

    private String baseUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        return base.replaceAll("/+$", "");
    }

    private String get(String url) {
        throttle.acquire();
        try {
            WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(url);
            if (properties.hasApiKey()) {
                spec = spec.header(API_KEY_HEADER, properties.getApiKey().trim());
            }
            return spec
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getTimeoutMs())))
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientTonMetadataClient::isRetryable)
                            .transientErrors(true))
                    .block();
        } catch (Exception ex) {
            log.debug("TON metadata GET {} failed: {}", url, ex.getMessage());
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

    private static Integer intValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
