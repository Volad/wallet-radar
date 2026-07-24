package com.walletradar.platform.networks.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.RpcException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link TonRpcClient}.
 *
 * <p>Sends GET requests to the TON Center v3 API. When an API key is configured it is added
 * as the {@code X-API-Key} header on every request.</p>
 */
@Slf4j
public class WebClientTonRpcClient implements TonRpcClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * TON Center (free tier) intermittently returns 429/5xx or times out, which previously
     * failed the whole request and — during planning — silently skipped the TON source
     * (see {@code TonBlockHeightResolver} / {@code SourceSyncPlanner}). Retry transient
     * failures with exponential backoff so a single flaky response never drops TON ingestion.
     */
    private static final long RETRY_MAX_ATTEMPTS = 4;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(500);

    private final WebClient webClient;
    private final TonNetworkProperties properties;
    private final ObjectMapper objectMapper;

    /** 16 MB — TON Center can return very large pages for busy wallets. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    public WebClientTonRpcClient(WebClient.Builder builder,
                                  TonNetworkProperties properties,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
        WebClient.Builder configured = builder
                .baseUrl(properties.getBaseUrl())
                .exchangeStrategies(strategies);
        if (properties.hasApiKey()) {
            configured = configured.defaultHeader("X-API-Key", properties.getApiKey());
        }
        this.webClient = configured.build();
    }

    @Override
    public String get(String relativePath, Map<String, String> queryParams) {
        // Build an ABSOLUTE URL so the base path (/api/v3) is never dropped: WebClient's
        // DefaultUriBuilderFactory resolves a leading-slash path against the authority only,
        // which would silently strip /api/v3 from the configured base URL.
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().replaceAll("/+$", "");
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(base + "/" + relativePath);
        if (queryParams != null) {
            queryParams.forEach(uriBuilder::queryParam);
        }
        String uri = uriBuilder.build().toUriString();
        try {
            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                            .filter(WebClientTonRpcClient::isRetryable)
                            .transientErrors(true))
                    .onErrorMap(WebClientResponseException.class,
                            e -> new RpcException("TON Center HTTP " + e.getStatusCode()
                                    + " for " + relativePath + ": " + e.getMessage(), e))
                    .block();
            if (body == null || body.isBlank()) {
                throw new RpcException("TON Center returned empty body for " + relativePath);
            }
            return body;
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = rootCause(e);
            throw new RpcException("TON Center request failed for " + relativePath
                    + " [" + root.getClass().getSimpleName() + ": " + root.getMessage() + "]", e);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** Retry only on transient failures: 429/5xx, request I/O errors, and read timeouts. */
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
        return cause != null && (cause instanceof TimeoutException || cause instanceof IOException);
    }

    @Override
    public long getMasterchainSeqno() {
        try {
            String body = get("masterchainInfo", Map.of());
            JsonNode root = objectMapper.readTree(body);
            JsonNode seqno = root.path("last").path("seqno");
            if (seqno.isMissingNode() || seqno.isNull()) {
                throw new RpcException("getMasterchainInfo: missing last.seqno in response");
            }
            return seqno.asLong();
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("getMasterchainSeqno failed", e);
        }
    }
}
