package com.walletradar.ingestion.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionNetworkPropertiesTest {

    @Test
    void setNetwork_normalizesKeysToUpperCase() {
        IngestionNetworkProperties properties = new IngestionNetworkProperties();
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();

        properties.setNetwork(Map.of("zksync", entry));

        assertThat(properties.getNetwork()).containsKey("ZKSYNC");
        assertThat(properties.getNetwork().get("ZKSYNC")).isSameAs(entry);
    }
}
