package com.walletradar.application.cex.acquisition.venue.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.platform.networks.ReactorBlocking;
import com.walletradar.integration.config.BybitIntegrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Minimal signed Bybit V5 REST client used for integration validation and
 * history acquisition.
 */
@Component
@RequiredArgsConstructor
public class BybitApiClient {

    private static final String USER_OWNED_SUB = "USER_OWNED_SUB";

    private final WebClient.Builder webClientBuilder;
    private final BybitIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final Cache<String, List<BybitSubMember>> subMembersCache = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    /**
     * Discovers user-owned sub-accounts for a Bybit master UID. Results are cached per master UID for 24h.
     */
    public List<BybitSubMember> fetchSubMembers(String masterUid, String apiKey, String apiSecret) {
        String cacheKey = masterUid == null ? "" : masterUid.trim();
        if (cacheKey.isBlank()) {
            return List.of();
        }
        return subMembersCache.get(cacheKey, ignored -> loadSubMembers(apiKey, apiSecret));
    }

    public void invalidateSubMembersCache(String masterUid) {
        if (masterUid != null && !masterUid.isBlank()) {
            subMembersCache.invalidate(masterUid.trim());
        }
    }

    private List<BybitSubMember> loadSubMembers(String apiKey, String apiSecret) {
        List<BybitSubMember> discovered = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("pageLimit", 20);
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }
            JsonNode body = signedGet("/v5/user/query-sub-members", apiKey, apiSecret, params);
            JsonNode result = requireResult(body);
            JsonNode rows = result.path("subMembers");
            if (!rows.isArray()) {
                rows = result.path("list");
            }
            discovered.addAll(parseSubMembersResult(result));
            cursor = nextCursor(result);
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(discovered);
    }

    public CredentialInfo validateCredentials(String apiKey, String apiSecret) {
        JsonNode body = signedGet("/v5/user/query-api", apiKey, apiSecret, Map.of());
        JsonNode result = requireResult(body);
        String userId = text(result, "userID");
        boolean readOnly = result.path("readOnly").asInt(0) == 1 || result.path("readOnly").asBoolean(false);
        return new CredentialInfo(
                userId,
                readOnly,
                result.path("permissions"),
                text(result, "type")
        );
    }

    public BybitPage fetchStream(
            BybitIntegrationStream stream,
            String apiKey,
            String apiSecret,
            Instant fromTime,
            Instant toTime,
            String cursor
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", properties.getPageLimit());
        if (stream == BybitIntegrationStream.FUNDING_HISTORY) {
            params.put("createTimeFrom", fromTime.getEpochSecond());
            params.put("createTimeTo", toTime.getEpochSecond());
        } else {
            params.put("startTime", fromTime.toEpochMilli());
            params.put("endTime", toTime.toEpochMilli());
        }
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        if (stream == BybitIntegrationStream.TRANSACTION_LOG) {
            params.put("accountType", "UNIFIED");
        }
        if (stream.category() != null && !stream.category().isBlank()) {
            params.put("category", stream.category());
        }
        if (stream == BybitIntegrationStream.WITHDRAWAL) {
            params.put("withdrawType", 2);
        }

        JsonNode body = signedGet(pathFor(stream), apiKey, apiSecret, params);
        JsonNode result = requireResult(body);
        JsonNode rows = result.path("list");
        if (!rows.isArray() || rows.isMissingNode()) {
            rows = result.path("rows");
        }
        if (!rows.isArray()) {
            rows = objectMapper.createArrayNode();
        }
        return new BybitPage(rows, nextCursor(result));
    }

    /**
     * Cycle/5 N15: fetches the authoritative live balance per sub-account directly from Bybit. Used by the
     * dashboard to clamp ledger inventories so phantom positions left by API-gap defects (e.g. Earn-product
     * withdrawals that none of the FUNDING_HISTORY / WITHDRAWAL / TX_LOG / EARN_FLEXIBLE_SAVING streams expose)
     * cannot inflate the umbrella view above the real Bybit holding.
     */
    public LiveBybitBalances fetchLiveBalances(String apiKey, String apiSecret) {
        Map<String, BigDecimal> uta = fetchUnifiedBalances(apiKey, apiSecret);
        Map<String, BigDecimal> fund = fetchFundBalances(apiKey, apiSecret);
        Map<String, BigDecimal> earn = fetchEarnBalances(apiKey, apiSecret);
        Map<String, BigDecimal> umbrella = new LinkedHashMap<>();
        accumulate(umbrella, uta);
        accumulate(umbrella, fund);
        accumulate(umbrella, earn);
        return new LiveBybitBalances(uta, fund, earn, umbrella, Instant.now());
    }

    private Map<String, BigDecimal> fetchUnifiedBalances(String apiKey, String apiSecret) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountType", "UNIFIED");
        JsonNode body = signedGet("/v5/account/wallet-balance", apiKey, apiSecret, params);
        JsonNode result = requireResult(body);
        for (JsonNode account : result.path("list")) {
            for (JsonNode coin : account.path("coin")) {
                String symbol = upper(text(coin, "coin"));
                BigDecimal qty = decimal(coin, "walletBalance");
                if (symbol != null && !symbol.isBlank() && qty != null && qty.signum() > 0) {
                    out.merge(symbol, qty, BigDecimal::add);
                }
            }
        }
        return out;
    }

    private Map<String, BigDecimal> fetchFundBalances(String apiKey, String apiSecret) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountType", "FUND");
        JsonNode body = signedGet("/v5/asset/transfer/query-account-coins-balance", apiKey, apiSecret, params);
        JsonNode result = requireResult(body);
        for (JsonNode coin : result.path("balance")) {
            String symbol = upper(text(coin, "coin"));
            BigDecimal qty = decimal(coin, "walletBalance");
            if (symbol != null && !symbol.isBlank() && qty != null && qty.signum() > 0) {
                out.merge(symbol, qty, BigDecimal::add);
            }
        }
        return out;
    }

    private Map<String, BigDecimal> fetchEarnBalances(String apiKey, String apiSecret) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String category : new String[]{"FlexibleSaving", "OnChain"}) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("category", category);
            JsonNode body;
            try {
                body = signedGet("/v5/earn/position", apiKey, apiSecret, params);
            } catch (RuntimeException ex) {
                // Earn position endpoint may return error for accounts without products in a category.
                continue;
            }
            JsonNode result = body.path("result");
            for (JsonNode pos : result.path("list")) {
                String symbol = upper(text(pos, "coin"));
                BigDecimal qty = decimal(pos, "amount");
                if (symbol != null && !symbol.isBlank() && qty != null && qty.signum() > 0) {
                    out.merge(symbol, qty, BigDecimal::add);
                }
            }
        }
        return out;
    }

    private static void accumulate(Map<String, BigDecimal> dest, Map<String, BigDecimal> src) {
        for (Map.Entry<String, BigDecimal> entry : src.entrySet()) {
            dest.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
        }
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        String raw = field.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode signedGet(String path, String apiKey, String apiSecret, Map<String, Object> params) {
        String queryString = canonicalQueryString(params);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String recvWindow = String.valueOf(properties.getRecvWindowMs());
        String signature = sign(timestamp + apiKey + recvWindow + queryString, apiSecret);
        URI uri = buildRequestUri(properties.getBaseUrl(), path, queryString);
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        Mono<JsonNode> request = client.get()
                .uri(uri)
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-RECV-WINDOW", recvWindow)
                .header("X-BAPI-SIGN", signature)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorResume(error -> Mono.error(new IllegalStateException("Bybit request failed: " + error.getMessage(), error)));
        return ReactorBlocking.block(
                request,
                Duration.ofMillis(properties.getRequestTimeoutMs())
        );
    }

    static List<BybitSubMember> parseSubMembersResult(JsonNode result) {
        if (result == null || result.isMissingNode()) {
            return List.of();
        }
        JsonNode rows = result.path("subMembers");
        if (!rows.isArray()) {
            rows = result.path("list");
        }
        if (!rows.isArray()) {
            return List.of();
        }
        List<BybitSubMember> discovered = new ArrayList<>();
        for (JsonNode row : rows) {
            String memberType = textStatic(row, "memberType");
            if (!USER_OWNED_SUB.equalsIgnoreCase(memberType)) {
                continue;
            }
            String uid = textStatic(row, "uid");
            if (uid == null || uid.isBlank()) {
                continue;
            }
            discovered.add(new BybitSubMember(uid.trim(), textStatic(row, "username"), memberType));
        }
        return List.copyOf(discovered);
    }

    private static String textStatic(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

    static String canonicalQueryString(Map<String, Object> params) {
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            normalized.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return normalized.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    static URI buildRequestUri(String baseUrl, String path, String queryString) {
        String separator = queryString == null || queryString.isBlank() ? "" : "?" + queryString;
        return URI.create(baseUrl + path + separator);
    }

    private JsonNode requireResult(JsonNode body) {
        if (body == null || body.path("retCode").asInt(-1) != 0) {
            String message = body == null ? "Empty Bybit response" : text(body, "retMsg");
            throw new IllegalStateException("Bybit API rejected request: " + message);
        }
        return body.path("result");
    }

    private String nextCursor(JsonNode result) {
        String next = text(result, "nextPageCursor");
        if (next == null || next.isBlank()) {
            next = text(result, "nextCursor");
        }
        if (next == null || next.isBlank()) {
            next = text(result, "cursor");
        }
        return next == null || next.isBlank() ? null : next;
    }

    private String pathFor(BybitIntegrationStream stream) {
        return switch (stream) {
            case TRANSACTION_LOG -> "/v5/account/transaction-log";
            case EXECUTION_LINEAR, EXECUTION_INVERSE, EXECUTION_SPOT, EXECUTION_OPTION -> "/v5/execution/list";
            case FUNDING_HISTORY -> "/v5/asset/fundinghistory";
            case INTERNAL_TRANSFER -> "/v5/asset/transfer/query-inter-transfer-list";
            case UNIVERSAL_TRANSFER -> "/v5/asset/transfer/query-universal-transfer-list";
            case DEPOSIT_ONCHAIN -> "/v5/asset/deposit/query-record";
            case DEPOSIT_INTERNAL -> "/v5/asset/deposit/query-internal-record";
            case WITHDRAWAL -> "/v5/asset/withdraw/query-record";
            case CONVERT_HISTORY -> "/v5/asset/exchange/query-convert-history";
            case EARN_FLEXIBLE_SAVING -> "/v5/earn/order";
        };
    }

    private String sign(String payload, String secret) {
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
            throw new IllegalStateException("Unable to sign Bybit request", exception);
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

    public record CredentialInfo(
            String userId,
            boolean readOnly,
            JsonNode permissions,
            String accountType
    ) {
    }

    public record BybitPage(
            JsonNode rows,
            String nextCursor
    ) {
    }

    /**
     * Cycle/5 N15: snapshot of authoritative live Bybit balances per sub-account, plus the umbrella sum.
     *
     * @param uta      symbol → quantity from {@code /v5/account/wallet-balance accountType=UNIFIED}
     * @param fund     symbol → quantity from {@code /v5/asset/transfer/query-account-coins-balance accountType=FUND}
     * @param earn     symbol → quantity from {@code /v5/earn/position} across all categories
     * @param umbrella symbol → sum of UTA + FUND + EARN (no sub-account split; used by dashboard clamp)
     * @param fetchedAt timestamp of the snapshot fetch
     */
    public record LiveBybitBalances(
            Map<String, BigDecimal> uta,
            Map<String, BigDecimal> fund,
            Map<String, BigDecimal> earn,
            Map<String, BigDecimal> umbrella,
            Instant fetchedAt
    ) {
    }
}
