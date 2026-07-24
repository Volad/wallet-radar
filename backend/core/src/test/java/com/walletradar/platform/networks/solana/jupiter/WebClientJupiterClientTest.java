package com.walletradar.platform.networks.solana.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing / resilience coverage for {@link WebClientJupiterClient}: Price v3 {@code usdPrice}
 * parsing (and defensive v2 {@code data.<mint>.price}), token metadata parsing, transient-failure
 * retry, and never-throw behaviour (errors resolve to empty results).
 */
class WebClientJupiterClientTest {

    private static final String MINT_A = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final String MINT_B = "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JupiterProperties properties() {
        JupiterProperties props = new JupiterProperties();
        props.setApiKey("");
        return props;
    }

    private WebClientJupiterClient client(AtomicInteger calls, Deque<ClientResponse> responses) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            ClientResponse next = responses.poll();
            return Mono.just(next != null ? next : jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
        });
        return new WebClientJupiterClient(builder, properties(), new JupiterRequestThrottle(0L), objectMapper);
    }

    @Test
    @DisplayName("fetchPrices parses Price v3 usdPrice shape and skips non-positive prices")
    void fetchPrices_parsesV3() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.OK,
                "{\"" + MINT_A + "\":{\"usdPrice\":214.53,\"decimals\":9},"
                        + "\"" + MINT_B + "\":{\"usdPrice\":0}}"));

        Map<String, BigDecimal> prices = client(calls, responses).fetchPrices(List.of(MINT_A, MINT_B));

        assertThat(prices).containsOnlyKeys(MINT_A);
        assertThat(prices.get(MINT_A)).isEqualByComparingTo("214.53");
    }

    @Test
    @DisplayName("fetchPrices parses defensive v2 data.<mint>.price shape")
    void fetchPrices_parsesV2DataShape() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.OK,
                "{\"data\":{\"" + MINT_A + "\":{\"price\":\"1.23\"}}}"));

        Map<String, BigDecimal> prices = client(calls, responses).fetchPrices(List.of(MINT_A));

        assertThat(prices.get(MINT_A)).isEqualByComparingTo("1.23");
    }

    @Test
    @DisplayName("fetchPrices retries on HTTP 429 then succeeds")
    void fetchPrices_retriesOn429() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.TOO_MANY_REQUESTS, "rate limited"));
        responses.add(jsonResponse(HttpStatus.OK, "{\"" + MINT_A + "\":{\"usdPrice\":5}}"));

        Map<String, BigDecimal> prices = client(calls, responses).fetchPrices(List.of(MINT_A));

        assertThat(prices.get(MINT_A)).isEqualByComparingTo("5");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fetchPrices returns empty (never throws) on persistent 5xx")
    void fetchPrices_emptyOnError() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        Map<String, BigDecimal> prices = client(calls, responses).fetchPrices(List.of(MINT_A));
        assertThat(prices).isEmpty();
    }

    @Test
    @DisplayName("fetchTokenMetadata parses the Tokens v2 search array and selects the mint by id")
    void fetchTokenMetadata_parsesV2SearchArray() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.OK,
                "[{\"id\":\"" + MINT_B + "\",\"symbol\":\"bSOL\",\"decimals\":9,\"name\":\"BlazeStake\"},"
                        + "{\"id\":\"" + MINT_A + "\",\"symbol\":\"mSOL\",\"decimals\":9,"
                        + "\"name\":\"Marinade staked SOL\",\"usdPrice\":105.9}]"));

        Optional<JupiterClient.JupiterTokenMetadata> metadata = client(calls, responses).fetchTokenMetadata(MINT_A);

        assertThat(metadata).isPresent();
        assertThat(metadata.get().symbol()).isEqualTo("mSOL");
        assertThat(metadata.get().decimals()).isEqualTo(9);
        assertThat(metadata.get().name()).isEqualTo("Marinade staked SOL");
    }

    @Test
    @DisplayName("fetchTokenMetadata returns empty when no array element matches the mint id")
    void fetchTokenMetadata_emptyWhenNoIdMatch() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.OK,
                "[{\"id\":\"" + MINT_B + "\",\"symbol\":\"bSOL\",\"decimals\":9}]"));

        assertThat(client(calls, responses).fetchTokenMetadata(MINT_A)).isEmpty();
    }

    @Test
    @DisplayName("fetchTokenMetadata returns empty (never throws) on 404")
    void fetchTokenMetadata_emptyOn404() {
        AtomicInteger calls = new AtomicInteger();
        Deque<ClientResponse> responses = new ArrayDeque<>();
        responses.add(jsonResponse(HttpStatus.NOT_FOUND, "not found"));

        assertThat(client(calls, responses).fetchTokenMetadata(MINT_A)).isEmpty();
    }

    @Test
    @DisplayName("disabled properties short-circuit without any HTTP call")
    void disabled_shortCircuits() {
        JupiterProperties disabled = new JupiterProperties();
        disabled.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.OK, "{}"));
        });
        WebClientJupiterClient client =
                new WebClientJupiterClient(builder, disabled, new JupiterRequestThrottle(0L), objectMapper);

        assertThat(client.fetchPrices(List.of(MINT_A))).isEmpty();
        assertThat(client.fetchTokenMetadata(MINT_A)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    private static ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
