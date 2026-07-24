package com.walletradar.application.liquiditypools.enrichment.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal read-only client for the free Raydium v3 public API ({@code https://api-v3.raydium.io}).
 * Resolves CLMM pool metadata (token mints, symbols, decimals, price) for a pool id via
 * {@code /pools/info/ids}.
 *
 * <p>No API key is required. Never throws: any transport/parse failure resolves to
 * {@link Optional#empty()} so the caller keeps the existing stale/shell snapshot.</p>
 */
@Component
@Slf4j
public class RaydiumClmmApiClient {

    private final WebClient webClient;
    private final Duration requestTimeout;
    private final boolean enabled;

    public RaydiumClmmApiClient(WebClient.Builder builder, LiquidityPoolsProperties properties) {
        LiquidityPoolsProperties.Solana solana = properties.getSolana();
        this.webClient = builder.baseUrl(solana.getRaydiumBaseUrl()).build();
        this.requestTimeout = Duration.ofMillis(Math.max(1L, solana.getTimeoutMs()));
        this.enabled = solana.isEnabled();
    }

    /**
     * Fetches CLMM pool metadata by pool id.
     *
     * @return empty when disabled, blank input, the pool is unknown, or the request fails.
     */
    public Optional<RaydiumPool> fetchPool(String poolId) {
        if (!enabled || poolId == null || poolId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode body = webClient.get()
                    .uri("/pools/info/ids?ids={ids}", poolId.trim())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(requestTimeout);
            return parse(body);
        } catch (RuntimeException ex) {
            log.debug("Raydium CLMM pool fetch failed: pool={} error={}", poolId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<RaydiumPool> parse(JsonNode body) {
        if (body == null || !body.path("success").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode data = body.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode pool = data.get(0);
        RaydiumToken mintA = token(pool.path("mintA"));
        RaydiumToken mintB = token(pool.path("mintB"));
        if (mintA == null || mintB == null) {
            return Optional.empty();
        }
        return Optional.of(new RaydiumPool(mintA, mintB, decimal(pool.path("price"))));
    }

    private static RaydiumToken token(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String mint = text(node, "address");
        if (mint == null) {
            return null;
        }
        Integer decimals = node.path("decimals").isNumber() ? node.path("decimals").asInt() : null;
        return new RaydiumToken(mint, text(node, "symbol"), decimals);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            String txt = value.asText().trim();
            return txt.isBlank() ? null : txt;
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node) {
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

    /** CLMM pool metadata needed to enrich a position snapshot. */
    public record RaydiumPool(RaydiumToken mintA, RaydiumToken mintB, BigDecimal price) {
    }

    /** A CLMM pool token side (mint, symbol, decimals). */
    public record RaydiumToken(String mint, String symbol, Integer decimals) {
    }
}
