package com.walletradar.integration.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BybitApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void parseSubMembersKeepsOnlyUserOwnedSubEntries() throws Exception {
        String json = """
                {
                  "subMembers": [
                    {"uid": "516601508", "username": "sub-a", "memberType": "USER_OWNED_SUB"},
                    {"uid": "999999999", "username": "agent", "memberType": "AGENT_SUB"},
                    {"uid": "421768407", "username": "sub-b", "memberType": "USER_OWNED_SUB"}
                  ]
                }
                """;
        JsonNode result = objectMapper.readTree(json);

        List<BybitSubMember> members = BybitApiClient.parseSubMembersResult(result);

        assertThat(members).extracting(BybitSubMember::uid)
                .containsExactly("516601508", "421768407");
        assertThat(members).allMatch(member -> "USER_OWNED_SUB".equals(member.memberType()));
    }
}
