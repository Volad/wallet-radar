package com.walletradar.application.cex.acquisition.venue.dzengi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DzengiApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void canonicalQueryStringSortsAndEncodesParametersDeterministically() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", 100);
        params.put("startTime", 1742129391000L);
        params.put("endTime", 1742734191000L);
        params.put("recvWindow", 5000L);
        params.put("timestamp", 1742734191999L);
        params.put("symbol", "USD/BYN");

        String queryString = DzengiApiClient.canonicalQueryString(params);

        assertThat(queryString)
                .isEqualTo(
                        "endTime=1742734191000&limit=100&recvWindow=5000&startTime=1742129391000"
                                + "&symbol=USD%2FBYN&timestamp=1742734191999"
                );
    }

    @Test
    void canonicalQueryStringDoubleEncodesPreEncodedCursorValue() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", 50);
        params.put("cursor", "1921049%3A1%2C1921036%3A0");

        String queryString = DzengiApiClient.canonicalQueryString(params);

        assertThat(queryString)
                .isEqualTo("cursor=1921049%253A1%252C1921036%253A0&limit=50");
    }

    @Test
    void signPayloadProducesDeterministicHmacSha256Hex() {
        String payload = "endTime=1742734191000&limit=100&recvWindow=5000&startTime=1742129391000"
                + "&symbol=USD%2FBYN&timestamp=1742734191999";
        String secret = "test-api-secret";

        String signature = DzengiApiClient.signPayload(payload, secret);

        assertThat(signature)
                .isEqualTo("301ef8556834d275e0aa6b738b9c9df1d40e4ca78b66837be98d6aa61557982a");
    }

    @Test
    void isBenignMyTradesErrorTreatsInvalidSymbolAndTimeWindowAsEmpty() {
        assertThat(DzengiApiClient.isBenignMyTradesError(400, "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"))
                .isTrue();
        assertThat(DzengiApiClient.isBenignMyTradesError(
                400,
                "{\"code\":-1128,\"msg\":\"Time between startTime and endTime must be less than 1 hour\"}"
        )).isTrue();
        assertThat(DzengiApiClient.isBenignMyTradesError(403, "{\"code\":-1025,\"msg\":\"Invalid API-key\"}"))
                .isFalse();
    }

    @Test
    void filterRowsByTimeKeepsRowsInsideSegmentWindow() throws Exception {
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        rows.add(OBJECT_MAPPER.readTree("{\"id\":1,\"time\":1000}"));
        rows.add(OBJECT_MAPPER.readTree("{\"id\":2,\"time\":2500}"));
        rows.add(OBJECT_MAPPER.readTree("{\"id\":3,\"time\":4000}"));

        var filtered = DzengiApiClient.filterRowsByTime(
                rows,
                Instant.ofEpochMilli(2000),
                Instant.ofEpochMilli(3500)
        );

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("id").asInt()).isEqualTo(2);
    }
}
