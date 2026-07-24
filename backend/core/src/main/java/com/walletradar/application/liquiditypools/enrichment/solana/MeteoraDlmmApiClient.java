package com.walletradar.application.liquiditypools.enrichment.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.walletradar.application.liquiditypools.config.LiquidityPoolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal read-only client for the free Meteora DLMM public data API
 * ({@code https://dlmm.datapi.meteora.ag}). Resolves pool metadata (token mints, symbols, decimals,
 * current price, bin step) for a DLMM pool (lbPair) address.
 *
 * <p>No API key is required. Never throws: any transport/parse failure resolves to
 * {@link Optional#empty()} so the caller keeps the existing stale/shell snapshot.</p>
 */
@Component
@Slf4j
public class MeteoraDlmmApiClient {

    private final WebClient webClient;
    private final Duration requestTimeout;
    private final boolean enabled;

    public MeteoraDlmmApiClient(WebClient.Builder builder, LiquidityPoolsProperties properties) {
        LiquidityPoolsProperties.Solana solana = properties.getSolana();
        this.webClient = builder.baseUrl(solana.getMeteoraBaseUrl()).build();
        this.requestTimeout = Duration.ofMillis(Math.max(1L, solana.getTimeoutMs()));
        this.enabled = solana.isEnabled();
    }

    /**
     * Fetches DLMM pool metadata by lbPair address.
     *
     * @return empty when disabled, blank input, the pool is unknown, or the request fails.
     */
    public Optional<MeteoraPool> fetchPool(String poolAddress) {
        return fetchPoolResult(poolAddress).pool();
    }

    /**
     * Fetches DLMM pool metadata by lbPair address, distinguishing a definitive
     * {@link Availability#NOT_FOUND HTTP 404} (the pool/position no longer exists on Meteora — a
     * durable closed signal) from a transient {@link Availability#UNAVAILABLE} failure (timeout,
     * 5xx, parse error) where the caller must keep the existing stale snapshot rather than assume
     * closure. Never throws.
     */
    public MeteoraPoolResult fetchPoolResult(String poolAddress) {
        if (!enabled || poolAddress == null || poolAddress.isBlank()) {
            return MeteoraPoolResult.unavailable();
        }
        try {
            JsonNode body = webClient.get()
                    .uri("/pools/{address}", poolAddress.trim())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(requestTimeout);
            return parse(body)
                    .map(MeteoraPoolResult::resolved)
                    .orElseGet(MeteoraPoolResult::unavailable);
        } catch (WebClientResponseException.NotFound notFound) {
            // A durable "pool not found" — the DLMM lbPair the position referenced is gone, which for
            // an entry-only position with no captured LP_EXIT is the only reliable closed signal.
            log.debug("Meteora DLMM pool not found (treated as closed): pool={}", poolAddress);
            return MeteoraPoolResult.notFound();
        } catch (RuntimeException ex) {
            log.debug("Meteora DLMM pool fetch failed: pool={} error={}", poolAddress, ex.getMessage());
            return MeteoraPoolResult.unavailable();
        }
    }

    private static Optional<MeteoraPool> parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            return Optional.empty();
        }
        MeteoraToken tokenX = token(body.path("token_x"));
        MeteoraToken tokenY = token(body.path("token_y"));
        if (tokenX == null || tokenY == null) {
            return Optional.empty();
        }
        return Optional.of(new MeteoraPool(
                text(body, "name"),
                tokenX,
                tokenY,
                decimal(body.path("current_price")),
                integerOrNull(body.path("pool_config").path("bin_step")),
                decimal(body.path("pool_config").path("base_fee_pct"))
        ));
    }

    private static MeteoraToken token(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String mint = text(node, "address");
        if (mint == null) {
            return null;
        }
        return new MeteoraToken(mint, text(node, "symbol"), integerOrNull(node.path("decimals")));
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

    private static Integer integerOrNull(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    /** Distinguishes a resolved pool, a durable 404 (closed), and a transient failure. */
    public enum Availability { RESOLVED, NOT_FOUND, UNAVAILABLE }

    /** Result of a pool fetch carrying availability so callers can tell closed from transient. */
    public record MeteoraPoolResult(Availability availability, Optional<MeteoraPool> pool) {
        static MeteoraPoolResult resolved(MeteoraPool pool) {
            return new MeteoraPoolResult(Availability.RESOLVED, Optional.of(pool));
        }

        static MeteoraPoolResult notFound() {
            return new MeteoraPoolResult(Availability.NOT_FOUND, Optional.empty());
        }

        static MeteoraPoolResult unavailable() {
            return new MeteoraPoolResult(Availability.UNAVAILABLE, Optional.empty());
        }
    }

    /** DLMM pool metadata needed to enrich a position snapshot. */
    public record MeteoraPool(
            String name,
            MeteoraToken tokenX,
            MeteoraToken tokenY,
            BigDecimal currentPrice,
            Integer binStep,
            BigDecimal baseFeePct
    ) {
    }

    /** A DLMM pool token side (mint, symbol, decimals). */
    public record MeteoraToken(String mint, String symbol, Integer decimals) {
    }
}
