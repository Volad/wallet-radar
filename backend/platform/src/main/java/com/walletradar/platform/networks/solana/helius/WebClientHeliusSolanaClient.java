package com.walletradar.platform.networks.solana.helius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.RpcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link HeliusSolanaClient}.
 *
 * <p>Transaction history paginates via signature cursor ({@code before} query param).
 * Parse endpoint accepts a JSON body with a {@code transactions} array of signatures.</p>
 */
@Slf4j
public class WebClientHeliusSolanaClient implements HeliusSolanaClient {

    private final WebClient webClient;
    private final HeliusSolanaProperties properties;
    private final HeliusRequestThrottle throttle;
    private final ObjectMapper objectMapper;

    /** 16 MB — Helius enhanced transactions can return very large pages for active wallets. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    /** Per-request read timeout; a hung read is retried as a transient failure. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Helius (free tier) returns HTTP 429 TOO_MANY_REQUESTS under load — e.g. during a 2-year
     * resync — and can intermittently 5xx or time out. Previously the response was mapped straight
     * to {@link RpcException}, failing the (now single) backfill segment. Retry only transient
     * failures (429 / 5xx / request I/O / read timeout) with exponential backoff so a rate limit
     * recovers within one fetch pass. Non-retryable 4xx and exhausted retries still surface as
     * {@link RpcException}. The client-side {@link HeliusRequestThrottle} additionally spaces out
     * requests so the burst rate stays under the limit in the first place.
     */
    private static final long RETRY_MAX_ATTEMPTS = 6;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofSeconds(1);

    public WebClientHeliusSolanaClient(WebClient.Builder builder,
                                        HeliusSolanaProperties properties,
                                        HeliusRequestThrottle throttle,
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
    public List<JsonNode> getTransactionHistory(String address, String before, int limit) {
        String urlTemplate = properties.resolvedParseTransactionsHistoryUrl();
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new RpcException("Helius parseTransactionsHistoryUrl is not configured");
        }
        String url = urlTemplate.replace("{address}", address);
        // Normalize: strip trailing slash before the query string to avoid Helius 404s
        url = url.replace("/?", "?").replaceAll("/$", "");
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(url)
                .queryParam("limit", Math.min(limit, 100));
        if (before != null && !before.isBlank()) {
            uriBuilder.queryParam("before", before);
        }
        throttle.acquire();
        try {
            String body = webClient.get()
                    .uri(uriBuilder.toUriString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientHeliusSolanaClient::isRetryable)
                            .transientErrors(true))
                    .onErrorMap(WebClientResponseException.class,
                            e -> new RpcException("Helius history HTTP " + e.getStatusCode() + ": " + e.getMessage(), e))
                    .block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                log.warn("Helius transaction history response is not an array for address {}", address);
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>(root.size());
            root.forEach(result::add);
            return result;
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("Helius getTransactionHistory failed for " + address, e);
        }
    }

    @Override
    public List<JsonNode> parseTransactions(List<String> signatures) {
        String url = properties.resolvedParseTransactionsUrl();
        if (url == null || url.isBlank()) {
            throw new RpcException("Helius parseTransactionsUrl is not configured");
        }
        // Normalize: strip trailing slash before the query string (and any bare trailing slash)
        // to avoid Helius 404s, mirroring getTransactionHistory. Configured URLs may carry a
        // trailing slash (e.g. ".../v0/transactions/?api-key=…").
        url = url.replace("/?", "?").replaceAll("/$", "");
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }
        throttle.acquire();
        try {
            Map<String, Object> body = Map.of("transactions", signatures);
            String response = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientHeliusSolanaClient::isRetryable)
                            .transientErrors(true))
                    .onErrorMap(WebClientResponseException.class,
                            e -> new RpcException("Helius parse HTTP " + e.getStatusCode() + ": " + e.getMessage(), e))
                    .block();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                log.warn("Helius parseTransactions response is not an array");
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>(root.size());
            root.forEach(result::add);
            return result;
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("Helius parseTransactions failed", e);
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
}
