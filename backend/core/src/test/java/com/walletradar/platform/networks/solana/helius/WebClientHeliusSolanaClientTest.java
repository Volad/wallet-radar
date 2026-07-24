package com.walletradar.platform.networks.solana.helius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.RpcException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the transient-failure backoff retry added to {@link WebClientHeliusSolanaClient}
 * (FIX B for the Solana 2-year-resync 429 storm): both {@code getTransactionHistory} and
 * {@code parseTransactions} retry HTTP 429 / 5xx, but not other 4xx.
 */
class WebClientHeliusSolanaClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HeliusSolanaProperties properties() {
        HeliusSolanaProperties props = new HeliusSolanaProperties();
        props.setApiKey("test-key");
        return props;
    }

    private WebClientHeliusSolanaClient client(AtomicInteger calls, Deque<ClientResponse> responses) {
        return client(calls, responses, new HeliusRequestThrottle(0L));
    }

    private WebClientHeliusSolanaClient client(AtomicInteger calls, Deque<ClientResponse> responses,
                                               HeliusRequestThrottle throttle) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            ClientResponse next = responses.poll();
            return Mono.just(next != null ? next : jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        });
        return new WebClientHeliusSolanaClient(builder, properties(), throttle, objectMapper);
    }

    @Test
    @DisplayName("getTransactionHistory retries on HTTP 429 then succeeds")
    void getTransactionHistory_retriesOn429ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.TOO_MANY_REQUESTS, "rate limited"));
        responses.add(jsonResponse(HttpStatus.OK, "[{\"signature\":\"sigA\"}]"));

        List<JsonNode> result = client(calls, responses).getTransactionHistory("9Grpx4HK", null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("signature").asText()).isEqualTo("sigA");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("parseTransactions retries on HTTP 503 then succeeds")
    void parseTransactions_retriesOn5xxThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));
        responses.add(jsonResponse(HttpStatus.OK, "[{\"signature\":\"sigB\"}]"));

        List<JsonNode> result = client(calls, responses).parseTransactions(List.of("sigB"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("signature").asText()).isEqualTo("sigB");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Non-retryable 4xx (404) fails immediately without retry")
    void getTransactionHistory_doesNotRetryOn4xx() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.NOT_FOUND, "not found"));

        WebClientHeliusSolanaClient client = client(calls, responses);
        assertThatThrownBy(() -> client.getTransactionHistory("9Grpx4HK", null, 100))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("404");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Throttle spaces two consecutive requests by at least the configured interval")
    void getTransactionHistory_throttleEnforcesMinInterval() {
        long intervalMillis = 200L;
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.OK, "[{\"signature\":\"sig1\"}]"));
        responses.add(jsonResponse(HttpStatus.OK, "[{\"signature\":\"sig2\"}]"));

        WebClientHeliusSolanaClient client = client(calls, responses, new HeliusRequestThrottle(intervalMillis));

        long start = System.nanoTime();
        client.getTransactionHistory("9Grpx4HK", null, 100);
        client.getTransactionHistory("9Grpx4HK", "sig1", 100);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertThat(calls.get()).isEqualTo(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(intervalMillis);
    }

    @Test
    @DisplayName("min-request-interval-millis defaults to 250ms")
    void heliusProperties_minRequestIntervalDefault() {
        assertThat(new HeliusSolanaProperties().getMinRequestIntervalMillis()).isEqualTo(250L);
    }

    private static ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
