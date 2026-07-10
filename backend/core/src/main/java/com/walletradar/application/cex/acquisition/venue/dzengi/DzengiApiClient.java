package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.walletradar.application.cex.config.DzengiIntegrationProperties;
import com.walletradar.platform.networks.ReactorBlocking;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signed Dzengi REST client (Binance-compatible auth) with mandatory browser User-Agent.
 */
@Component
@RequiredArgsConstructor
public class DzengiApiClient {

    private final WebClient.Builder webClientBuilder;
    private final DzengiIntegrationProperties properties;
    private final ObjectMapper objectMapper;

    public CredentialInfo validateCredentials(String apiKey, String apiSecret) {
        JsonNode body = signedGet("/api/v1/account", apiKey, apiSecret, Map.of());
        String userId = text(body, "userId");
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Dzengi credential validation did not return userId");
        }
        boolean readOnly = !body.path("canTrade").asBoolean(true)
                || !body.path("canWithdraw").asBoolean(true);
        return new CredentialInfo(
                userId.trim(),
                readOnly,
                body.path("canDeposit").asBoolean(true),
                body.path("canTrade").asBoolean(true),
                body.path("canWithdraw").asBoolean(true)
        );
    }

    public DzengiPage fetchStream(
            DzengiIntegrationStream stream,
            String streamParam,
            String apiKey,
            String apiSecret,
            Instant fromTime,
            Instant toTime
    ) {
        return switch (stream) {
            case LEDGER -> fetchLedger(apiKey, apiSecret, fromTime, toTime);
            case DEPOSITS -> fetchDeposits(apiKey, apiSecret, fromTime, toTime);
            case WITHDRAWALS -> fetchWithdrawals(apiKey, apiSecret, fromTime, toTime);
            case MY_TRADES -> fetchMyTrades(apiKey, apiSecret, streamParam, fromTime, toTime);
            case MY_TRADES_V2 -> fetchMyTradesV2(apiKey, apiSecret, streamParam, fromTime, toTime);
            case TRADING_POSITIONS_HISTORY -> fetchTradingPositionsHistory(apiKey, apiSecret, fromTime, toTime);
            case EXCHANGE_INFO -> fetchExchangeInfoPage();
        };
    }

    public JsonNode fetchExchangeInfo() {
        return publicGet("/api/v1/exchangeInfo");
    }

    public LiveDzengiBalances fetchLiveBalances(String apiKey, String apiSecret) {
        JsonNode body = signedGet("/api/v1/account", apiKey, apiSecret, Map.of());
        Map<String, BigDecimal> umbrella = new LinkedHashMap<>();
        for (JsonNode balance : body.path("balances")) {
            String asset = upper(text(balance, "asset"));
            BigDecimal free = decimal(balance, "free");
            BigDecimal locked = decimal(balance, "locked");
            if (asset == null || asset.isBlank()) {
                continue;
            }
            BigDecimal total = (free == null ? BigDecimal.ZERO : free)
                    .add(locked == null ? BigDecimal.ZERO : locked);
            if (total.signum() > 0) {
                umbrella.merge(asset, total, BigDecimal::add);
            }
        }
        return new LiveDzengiBalances(umbrella, Instant.now());
    }

    public OptionalKline fetchKline(String symbol, Instant occurredAt) {
        long end = occurredAt.toEpochMilli();
        long start = end - Duration.ofDays(3).toMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("interval", "1d");
        params.put("startTime", start);
        params.put("endTime", end);
        params.put("limit", 5);
        JsonNode rows = publicGetKlinesArray(params);
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return OptionalKline.empty();
        }
        JsonNode best = rows.get(rows.size() - 1);
        if (!best.isArray() || best.size() < 5) {
            return OptionalKline.empty();
        }
        BigDecimal close = parseDecimal(best.get(4).asText());
        long openTime = best.get(0).asLong();
        if (close == null || close.signum() <= 0) {
            return OptionalKline.empty();
        }
        return new OptionalKline(close, Instant.ofEpochMilli(openTime));
    }

    private JsonNode publicGetKlinesArray(Map<String, Object> params) {
        String query = canonicalQueryString(params);
        // v2 klines covers all Dzengi equity/crypto symbols (v1 rejects GOOGL., NFLX., etc.)
        String path = "/api/v2/klines" + (query.isBlank() ? "" : "?" + query);
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        Mono<JsonNode> request = client.get()
                .uri(URI.create(properties.getBaseUrl() + path))
                .header("User-Agent", properties.getUserAgent())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(JsonNode.class);
                    }
                    return Mono.just(objectMapper.createArrayNode());
                })
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorResume(WebClientResponseException.class, ignored -> Mono.just(objectMapper.createArrayNode()));
        JsonNode body = ReactorBlocking.block(request, Duration.ofMillis(properties.getRequestTimeoutMs()));
        if (body != null && body.has("code") && body.path("code").asInt(0) != 0) {
            return objectMapper.createArrayNode();
        }
        return body == null ? objectMapper.createArrayNode() : body;
    }

    private DzengiPage fetchLedger(String apiKey, String apiSecret, Instant from, Instant to) {
        return signedPagedArray("/api/v1/ledger", apiKey, apiSecret, timeParams(from, to));
    }

    private DzengiPage fetchDeposits(String apiKey, String apiSecret, Instant from, Instant to) {
        return signedPagedArray("/api/v1/deposits", apiKey, apiSecret, timeParams(from, to));
    }

    private DzengiPage fetchWithdrawals(String apiKey, String apiSecret, Instant from, Instant to) {
        return signedPagedArray("/api/v1/withdrawals", apiKey, apiSecret, timeParams(from, to));
    }

    private DzengiPage fetchMyTrades(
            String apiKey,
            String apiSecret,
            String symbol,
            Instant from,
            Instant to
    ) {
        return fetchMyTradesAtPath("/api/v1/myTrades", apiKey, apiSecret, symbol, from, to);
    }

    private DzengiPage fetchMyTradesV2(
            String apiKey,
            String apiSecret,
            String symbol,
            Instant from,
            Instant to
    ) {
        return fetchMyTradesAtPath("/api/v2/myTrades", apiKey, apiSecret, symbol, from, to);
    }

    private DzengiPage fetchMyTradesAtPath(
            String path,
            String apiKey,
            String apiSecret,
            String symbol,
            Instant from,
            Instant to
    ) {
        if (symbol == null || symbol.isBlank()) {
            return DzengiPage.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        // Dzengi caps /myTrades at 100; larger values are rejected with -1128 "Invalid limit".
        params.put("limit", Math.min(Math.max(1, properties.getMyTradesMaxResults()), 100));
        JsonNode body = signedGetMyTrades(path, apiKey, apiSecret, params);
        return new DzengiPage(filterRowsByTime(body, from, to), null);
    }

    private DzengiPage fetchTradingPositionsHistory(String apiKey, String apiSecret, Instant from, Instant to) {
        Map<String, Object> params = timeParams(from, to);
        JsonNode body = signedGet("/api/v2/tradingPositionsHistory", apiKey, apiSecret, params);
        JsonNode history = body.path("history");
        if (!history.isArray()) {
            return DzengiPage.empty();
        }
        return new DzengiPage(history, null);
    }

    private DzengiPage fetchExchangeInfoPage() {
        JsonNode info = fetchExchangeInfo();
        JsonNode symbols = info.path("symbols");
        if (!symbols.isArray()) {
            return DzengiPage.empty();
        }
        return new DzengiPage(symbols, null);
    }

    private static Map<String, Object> timeParams(Instant from, Instant to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startTime", from.toEpochMilli());
        params.put("endTime", to.toEpochMilli());
        params.put("limit", 100);
        return params;
    }

    private DzengiPage signedPagedArray(
            String path,
            String apiKey,
            String apiSecret,
            Map<String, Object> params
    ) {
        Map<String, Object> effective = new LinkedHashMap<>(params);
        effective.putIfAbsent("limit", properties.getPageLimit());
        JsonNode body = signedGet(path, apiKey, apiSecret, effective);
        if (body.isArray()) {
            return new DzengiPage(body, null);
        }
        return DzengiPage.empty();
    }

    private JsonNode publicGet(String path) {
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        Mono<JsonNode> request = client.get()
                .uri(URI.create(properties.getBaseUrl() + path))
                .header("User-Agent", properties.getUserAgent())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
        return ReactorBlocking.block(request, Duration.ofMillis(properties.getRequestTimeoutMs()));
    }

    private JsonNode publicGetArray(String path, Map<String, Object> params) {
        String query = canonicalQueryString(params);
        String separator = query.isBlank() ? "" : "?" + query;
        return publicGet(path + separator);
    }

    private JsonNode signedGetMyTrades(String path, String apiKey, String apiSecret, Map<String, Object> params) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        Map<String, Object> signed = new LinkedHashMap<>(params);
        signed.put("recvWindow", properties.getRecvWindowMs());
        signed.put("timestamp", timestamp);
        String queryString = canonicalQueryString(signed);
        String signature = sign(queryString, apiSecret);
        URI uri = URI.create(properties.getBaseUrl() + path + "?" + queryString + "&signature=" + signature);
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        Mono<JsonNode> request = client.get()
                .uri(uri)
                .header("User-Agent", properties.getUserAgent())
                .header("X-MBX-APIKEY", apiKey)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(JsonNode.class);
                    }
                    return response.bodyToMono(String.class).flatMap(rawBody -> {
                        if (isBenignMyTradesError(response.statusCode().value(), rawBody)) {
                            return Mono.just(objectMapper.createArrayNode());
                        }
                        return Mono.error(new IllegalStateException(
                                "Dzengi myTrades failed: HTTP " + response.statusCode().value()
                                        + " body=" + rawBody
                        ));
                    });
                })
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorResume(WebClientResponseException.class, error -> {
                    if (isBenignMyTradesError(error.getStatusCode().value(), error.getResponseBodyAsString())) {
                        return Mono.just(objectMapper.createArrayNode());
                    }
                    return Mono.error(new IllegalStateException("Dzengi myTrades failed: " + error.getMessage(), error));
                });
        JsonNode body = ReactorBlocking.block(request, Duration.ofMillis(properties.getRequestTimeoutMs()));
        if (body != null && body.has("code") && body.path("code").asInt(0) != 0) {
            if (isBenignMyTradesError(200, body.toString())) {
                return objectMapper.createArrayNode();
            }
            throw new IllegalStateException("Dzengi API rejected myTrades: " + text(body, "msg"));
        }
        return body == null ? objectMapper.createArrayNode() : body;
    }

    static boolean isBenignMyTradesError(int statusCode, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return statusCode == 400;
        }
        if (!rawBody.contains("\"code\"")) {
            return false;
        }
        int code = extractErrorCode(rawBody);
        return code == -1121 || code == -1128;
    }

    private static int extractErrorCode(String rawBody) {
        int marker = rawBody.indexOf("\"code\"");
        if (marker < 0) {
            return 0;
        }
        int colon = rawBody.indexOf(':', marker);
        if (colon < 0) {
            return 0;
        }
        int end = colon + 1;
        while (end < rawBody.length() && !Character.isDigit(rawBody.charAt(end)) && rawBody.charAt(end) != '-') {
            end++;
        }
        int start = end;
        while (end < rawBody.length() && (Character.isDigit(rawBody.charAt(end)) || rawBody.charAt(end) == '-')) {
            end++;
        }
        if (start >= end) {
            return 0;
        }
        try {
            return Integer.parseInt(rawBody.substring(start, end));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static JsonNode filterRowsByTime(JsonNode rows, Instant from, Instant to) {
        if (rows == null || !rows.isArray()) {
            return rows;
        }
        if (from == null && to == null) {
            return rows;
        }
        long fromMs = from == null ? Long.MIN_VALUE : from.toEpochMilli();
        long toMs = to == null ? Long.MAX_VALUE : to.toEpochMilli();
        ArrayNode filtered = JsonNodeFactory.instance.arrayNode();
        for (JsonNode row : rows) {
            long timestamp = row.path("time").asLong(row.path("timestamp").asLong(Long.MIN_VALUE));
            if (timestamp >= fromMs && timestamp < toMs) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private JsonNode signedGet(String path, String apiKey, String apiSecret, Map<String, Object> params) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        Map<String, Object> signed = new LinkedHashMap<>(params);
        signed.put("recvWindow", properties.getRecvWindowMs());
        signed.put("timestamp", timestamp);
        String queryString = canonicalQueryString(signed);
        String signature = sign(queryString, apiSecret);
        URI uri = URI.create(properties.getBaseUrl() + path + "?" + queryString + "&signature=" + signature);
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        Mono<JsonNode> request = client.get()
                .uri(uri)
                .header("User-Agent", properties.getUserAgent())
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorResume(error -> Mono.error(
                        new IllegalStateException("Dzengi request failed: " + error.getMessage(), error)));
        JsonNode body = ReactorBlocking.block(request, Duration.ofMillis(properties.getRequestTimeoutMs()));
        if (body != null && body.has("code") && body.path("code").asInt(0) != 0) {
            throw new IllegalStateException("Dzengi API rejected request: " + text(body, "msg"));
        }
        return body == null ? objectMapper.createObjectNode() : body;
    }

    static String canonicalQueryString(Map<String, Object> params) {
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue());
            normalized.put(entry.getKey(), urlEncode(value));
        }
        return normalized.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sign(String payload, String secret) {
        return signPayload(payload, secret);
    }

    static String signPayload(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign Dzengi request", exception);
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        return parseDecimal(field.asText());
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record CredentialInfo(
            String userId,
            boolean readOnly,
            boolean canDeposit,
            boolean canTrade,
            boolean canWithdraw
    ) {
    }

    public record DzengiPage(JsonNode rows, String nextCursor) {
        public static DzengiPage empty() {
            return new DzengiPage(null, null);
        }

        public List<JsonNode> asList() {
            if (rows == null || !rows.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            rows.forEach(out::add);
            return out;
        }
    }

    public record LiveDzengiBalances(Map<String, BigDecimal> umbrella, Instant fetchedAt) {
    }

    public record OptionalKline(BigDecimal closePrice, Instant openTime) {
        public static OptionalKline empty() {
            return new OptionalKline(null, null);
        }

        public boolean isPresent() {
            return closePrice != null && closePrice.signum() > 0;
        }
    }
}
