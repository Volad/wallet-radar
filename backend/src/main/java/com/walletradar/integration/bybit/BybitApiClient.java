package com.walletradar.integration.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.integration.config.BybitIntegrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal signed Bybit V5 REST client used for integration validation and
 * history acquisition.
 */
@Component
@RequiredArgsConstructor
public class BybitApiClient {

    private final WebClient.Builder webClientBuilder;
    private final BybitIntegrationProperties properties;
    private final ObjectMapper objectMapper;

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

    private JsonNode signedGet(String path, String apiKey, String apiSecret, Map<String, Object> params) {
        String queryString = canonicalQueryString(params);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String recvWindow = String.valueOf(properties.getRecvWindowMs());
        String signature = sign(timestamp + apiKey + recvWindow + queryString, apiSecret);
        URI uri = buildRequestUri(properties.getBaseUrl(), path, queryString);
        WebClient client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        return client.get()
                .uri(uri)
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-RECV-WINDOW", recvWindow)
                .header("X-BAPI-SIGN", signature)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .onErrorResume(error -> Mono.error(new IllegalStateException("Bybit request failed: " + error.getMessage(), error)))
                .block();
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
}
