package com.walletradar.platform.networks.solana.jupiter.lend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.solana.jupiter.JupiterRequestThrottle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-backed {@link JupiterLendClient} mirroring the {@code WebClientJupiterClient} resilience
 * pattern (bounded buffer, per-request timeout, exponential backoff on transient failures, shared
 * client-side throttle). Never throws: all failures resolve to empty lists.
 *
 * <p>Risk params are decoded from the protocol's per-mille integer encoding ({@code 850 → 0.85}); supply
 * and borrow rates from its basis-point encoding ({@code 458 → 4.58}) so consumers read a true percent.</p>
 */
@Slf4j
public class WebClientJupiterLendClient implements JupiterLendClient {

    private static final int MAX_IN_MEMORY_SIZE = 8 * 1024 * 1024;
    private static final long RETRY_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_MIN_BACKOFF = Duration.ofMillis(500);
    private static final String API_KEY_HEADER = "x-api-key";
    private static final MathContext MC = MathContext.DECIMAL128;
    /** Protocol encodes risk params per-mille: 800 → 0.80, 850 → 0.85. */
    private static final BigDecimal PER_MILLE = BigDecimal.valueOf(1000);
    /**
     * Protocol encodes supply/borrow rates in basis points (hundredths of a percent): {@code 446 → 4.46%},
     * {@code 458 → 4.58%}. Cross-checked against the Earn API where {@code rewardsRate + supplyRate ==
     * totalRate} ({@code 71 + 351 == 422}). Decoded to a true percent here so consumers never re-scale.
     */
    private static final BigDecimal BASIS_POINTS_PER_PERCENT = BigDecimal.valueOf(100);

    private final WebClient webClient;
    private final JupiterLendProperties properties;
    private final JupiterRequestThrottle throttle;
    private final ObjectMapper objectMapper;

    public WebClientJupiterLendClient(WebClient.Builder builder,
                                      JupiterLendProperties properties,
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
    public List<BorrowVault> fetchBorrowVaults() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromUriString(properties.resolvedBaseUrl() + "/lend/v1/borrow/vaults")
                .queryParam("market", properties.resolvedMarket())
                .build()
                .toUriString();
        JsonNode root = getJson(url);
        JsonNode array = arrayNode(root);
        if (array == null) {
            return List.of();
        }
        List<BorrowVault> vaults = new ArrayList<>();
        for (JsonNode node : array) {
            BorrowVault vault = parseVault(node);
            if (vault != null) {
                vaults.add(vault);
            }
        }
        return List.copyOf(vaults);
    }

    @Override
    public List<BorrowPosition> fetchBorrowPositions(String walletAddress) {
        if (!properties.isEnabled() || walletAddress == null || walletAddress.isBlank()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromUriString(properties.resolvedBaseUrl() + "/lend/v1/borrow/positions")
                .queryParam("users", walletAddress.trim())
                .queryParam("market", properties.resolvedMarket())
                .build()
                .toUriString();
        JsonNode root = getJson(url);
        JsonNode array = arrayNode(root);
        if (array == null) {
            return List.of();
        }
        List<BorrowPosition> positions = new ArrayList<>();
        for (JsonNode node : array) {
            BorrowPosition position = parsePosition(node);
            if (position != null) {
                positions.add(position);
            }
        }
        return List.copyOf(positions);
    }

    private BorrowVault parseVault(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer vaultId = intOrNull(firstNumber(node, "id", "vaultId"));
        if (vaultId == null) {
            return null;
        }
        VaultToken supply = parseToken(firstObject(node, "supplyToken", "collateralToken", "supply"));
        VaultToken borrow = parseToken(firstObject(node, "borrowToken", "debtToken", "borrow"));
        BigDecimal collateralFactor = fraction(firstNumber(node, "collateralFactor", "collateralFactorPerMille", "ltv"));
        BigDecimal liquidationThreshold = fraction(firstNumber(node, "liquidationThreshold", "liquidationThresholdPerMille"));
        BigDecimal supplyRate = rateFraction(firstNumber(node, "supplyRate", "supplyApy", "supplyRatePct", "supplyApr"));
        BigDecimal borrowRate = rateFraction(firstNumber(node, "borrowRate", "borrowApy", "borrowRatePct", "borrowApr"));
        return new BorrowVault(vaultId, supply, borrow, collateralFactor, liquidationThreshold, supplyRate, borrowRate);
    }

    private VaultToken parseToken(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return new VaultToken(node.asText().trim(), null, null);
        }
        if (!node.isObject()) {
            return null;
        }
        String mint = firstText(node, "asset", "address", "mint", "id", "tokenAddress");
        String symbol = firstText(node, "symbol", "ticker");
        Integer decimals = intOrNull(firstNumber(node, "decimals", "decimal"));
        return new VaultToken(mint, symbol, decimals);
    }

    private BorrowPosition parsePosition(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer vaultId = intOrNull(firstNumber(node, "vaultId", "vault"));
        if (vaultId == null) {
            return null;
        }
        long nftId = longOrZero(firstNumber(node, "id", "nftId", "positionId"));
        BigInteger supply = bigIntOrNull(firstNode(node, "supply", "collateral", "supplyAmount"));
        BigInteger borrow = bigIntOrNull(firstNode(node, "borrow", "debt", "borrowAmount"));
        BigInteger dustBorrow = bigIntOrNull(firstNode(node, "dustBorrow", "dustDebt", "dustDebtAmount"));
        return new BorrowPosition(nftId, vaultId, supply, borrow, dustBorrow);
    }

    // --- JSON helpers -----------------------------------------------------------------------------

    private static JsonNode arrayNode(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        // Defensive: { "data": [...] } / { "positions": [...] } / { "vaults": [...] }
        for (String field : new String[]{"data", "positions", "vaults", "result"}) {
            JsonNode candidate = root.path(field);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private static JsonNode firstNode(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode firstObject(JsonNode node, String... fields) {
        JsonNode value = firstNode(node, fields);
        return value != null && (value.isObject() || value.isTextual()) ? value : null;
    }

    private static JsonNode firstNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && (value.isNumber() || (value.isTextual() && !value.asText().isBlank()))) {
                return value;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node) {
        BigDecimal value = decimalOrNull(node);
        return value == null ? null : value.intValue();
    }

    private static long longOrZero(JsonNode node) {
        BigDecimal value = decimalOrNull(node);
        return value == null ? 0L : value.longValue();
    }

    private static BigInteger bigIntOrNull(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isBigInteger()) {
                return node.bigIntegerValue();
            }
            if (node.isNumber()) {
                return node.decimalValue().toBigInteger();
            }
            String raw = node.asText(null);
            return raw == null || raw.isBlank() ? null : new BigInteger(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            String raw = node.asText(null);
            return raw == null || raw.isBlank() ? null : new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal fraction(JsonNode node) {
        BigDecimal value = decimalOrNull(node);
        return value == null ? null : value.divide(PER_MILLE, MC);
    }

    /** Decode a basis-point rate ({@code 458}) into a true percent ({@code 4.58}); null when absent. */
    static BigDecimal rateFraction(JsonNode node) {
        BigDecimal value = decimalOrNull(node);
        return value == null ? null : value.divide(BASIS_POINTS_PER_PERCENT, MC);
    }

    private JsonNode getJson(String url) {
        String body = get(url);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            log.debug("Jupiter Lend parse failed for {}: {}", url, ex.getMessage());
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
                            .filter(WebClientJupiterLendClient::isRetryable)
                            .transientErrors(true))
                    .block();
        } catch (Exception ex) {
            log.debug("Jupiter Lend GET {} failed: {}", url, ex.getMessage());
            return null;
        }
    }

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
