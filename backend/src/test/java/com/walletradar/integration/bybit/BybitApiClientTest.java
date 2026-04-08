package com.walletradar.integration.bybit;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BybitApiClientTest {

    @Test
    void preservesSingleEncodedCursorInCanonicalQueryAndRequestUri() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", 50);
        params.put("startTime", 1742129391000L);
        params.put("endTime", 1742734191000L);
        params.put("cursor", "1921049%3A1%2C1921036%3A0");
        params.put("accountType", "UNIFIED");

        String queryString = BybitApiClient.canonicalQueryString(params);
        URI uri = BybitApiClient.buildRequestUri(
                "https://api.bybit.com",
                "/v5/account/transaction-log",
                queryString
        );

        assertThat(queryString)
                .isEqualTo("accountType=UNIFIED&cursor=1921049%3A1%2C1921036%3A0&endTime=1742734191000&limit=50&startTime=1742129391000");
        assertThat(uri.toString())
                .isEqualTo("https://api.bybit.com/v5/account/transaction-log?accountType=UNIFIED&cursor=1921049%3A1%2C1921036%3A0&endTime=1742734191000&limit=50&startTime=1742129391000");
        assertThat(uri.toString()).doesNotContain("%253A").doesNotContain("%252C");
    }
}
