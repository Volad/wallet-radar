package com.walletradar.ingestion.config;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void setSyntheticNativeContracts_normalizesHexAndDecimalRepresentations() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();

        entry.setSyntheticNativeContracts(List.of(
                "0x000000000000000000000000000000000000800A",
                "32778",
                " 0x800a "
        ));

        assertThat(entry.getSyntheticNativeContracts())
                .containsExactly("0x000000000000000000000000000000000000800a");
    }

    @Test
    void setOneLegLendRules_normalizesContractAndSelector() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule rule =
                new IngestionNetworkProperties.NetworkIngestionEntry.OneLegLendRule();
        rule.setContract(" 0x2C7118C4C88B9841FCF839074C26AE8F035F2921 ");
        rule.setSelectors(List.of("f2b9fdb8", "0xF2B9FDB8", "invalid"));

        entry.setOneLegLendRules(List.of(rule));

        assertThat(entry.getOneLegLendRules()).hasSize(1);
        assertThat(entry.getOneLegLendRules().getFirst().getContract())
                .isEqualTo("0x2c7118c4c88b9841fcf839074c26ae8f035f2921");
        assertThat(entry.getOneLegLendRules().getFirst().getSelectors())
                .containsExactly("0xf2b9fdb8");
    }
}
