package com.walletradar.application.normalization.pipeline.classification.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.registry.CounterpartyHintLoader.CounterpartyKey;
import com.walletradar.application.normalization.pipeline.classification.support.KnownProtocolCounterpartyRegistry.ProtocolAttribution;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CounterpartyHintLoaderTest {

    private final CounterpartyHintLoader loader = new CounterpartyHintLoader(new ObjectMapper());

    @Test
    @DisplayName("networks defaults to network-agnostic when omitted")
    void networksDefaultsToWildcardWhenOmitted() {
        String json = """
                {
                  "entries": [
                    { "address": "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae", "category": "BRIDGE_ROUTER" }
                  ]
                }
                """;
        CounterpartyHintLoader.LoadedCounterpartyHints loaded = load(json);
        assertThat(loaded.bridgeRouters()).containsExactly("0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae");
    }

    @Test
    @DisplayName("PROTOCOL_COUNTERPARTY maps concrete (network, address) → attribution")
    void scopedCounterpartyMapsConcreteNetwork() {
        String json = """
                {
                  "entries": [
                    {
                      "address": "0x8c826f795466e39acbff1bb4eeeb759609377ba1",
                      "category": "PROTOCOL_COUNTERPARTY",
                      "networks": ["BASE"],
                      "protocol": "LI.FI",
                      "counterpartyType": "BRIDGE",
                      "asBridge": false
                    }
                  ]
                }
                """;
        CounterpartyHintLoader.LoadedCounterpartyHints loaded = load(json);
        assertThat(loaded.scopedCounterparties()).containsEntry(
                new CounterpartyKey(NetworkId.BASE, "0x8c826f795466e39acbff1bb4eeeb759609377ba1"),
                new ProtocolAttribution("LI.FI", "BRIDGE", false));
    }

    @Test
    @DisplayName("fails fast on an invalid address")
    void failsFastOnInvalidAddress() {
        String json = """
                { "entries": [ { "address": "not-an-address", "category": "BRIDGE_ROUTER" } ] }
                """;
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid counterparty hint address");
    }

    @Test
    @DisplayName("fails fast on an unknown category")
    void failsFastOnUnknownCategory() {
        String json = """
                { "entries": [ { "address": "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae", "category": "MYSTERY" } ] }
                """;
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported counterparty hint category");
    }

    @Test
    @DisplayName("fails fast when a network-agnostic category is scoped to concrete networks")
    void failsFastWhenNetworkAgnosticCategoryIsScoped() {
        String json = """
                {
                  "entries": [
                    { "address": "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae", "category": "BRIDGE_ROUTER", "networks": ["BASE"] }
                  ]
                }
                """;
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be network-agnostic");
    }

    @Test
    @DisplayName("fails fast when PROTOCOL_COUNTERPARTY uses the wildcard network")
    void failsFastWhenScopedCounterpartyUsesWildcard() {
        String json = """
                {
                  "entries": [
                    {
                      "address": "0x8c826f795466e39acbff1bb4eeeb759609377ba1",
                      "category": "PROTOCOL_COUNTERPARTY",
                      "networks": ["*"],
                      "protocol": "LI.FI",
                      "counterpartyType": "BRIDGE"
                    }
                  ]
                }
                """;
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must declare concrete networks");
    }

    @Test
    @DisplayName("fails fast on a duplicate (network, address) scoped counterparty")
    void failsFastOnDuplicateScopedCounterparty() {
        String json = """
                {
                  "entries": [
                    { "address": "0x8c826f795466e39acbff1bb4eeeb759609377ba1", "category": "PROTOCOL_COUNTERPARTY",
                      "networks": ["BASE"], "protocol": "LI.FI", "counterpartyType": "BRIDGE" },
                    { "address": "0x8c826f795466e39acbff1bb4eeeb759609377ba1", "category": "PROTOCOL_COUNTERPARTY",
                      "networks": ["BASE"], "protocol": "Other", "counterpartyType": "PROTOCOL" }
                  ]
                }
                """;
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate PROTOCOL_COUNTERPARTY mapping");
    }

    private CounterpartyHintLoader.LoadedCounterpartyHints load(String json) {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "inline");
    }
}
