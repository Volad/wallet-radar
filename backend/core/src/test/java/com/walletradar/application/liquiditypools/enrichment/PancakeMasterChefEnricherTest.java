package com.walletradar.application.liquiditypools.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PancakeMasterChefEnricherTest {

    @Test
    @DisplayName("golden set: pancake.json carries the 6 MasterChef V3 addresses per network")
    void configGoldenSet() {
        ProtocolResourceCatalog catalog = new ProtocolResourceLoader(new ObjectMapper());
        var definition = catalog.find("PancakeSwap", "v3").orElseThrow();

        assertThat(definition.contractSet("BSC"))
                .containsExactly("0x556b9306565093c855aea9ae92a594704c2cd59e");
        assertThat(definition.contractSet("ETHEREUM"))
                .containsExactly("0xe9c7f3196ab8c09f6616365e8873daeb207c0391");
        assertThat(definition.contractSet("ARBITRUM"))
                .containsExactly("0x5e09acf80c0296740ec5d6f643005a4ef8dcaa75");
        assertThat(definition.contractSet("BASE"))
                .containsExactly("0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3");
        assertThat(definition.contractSet("ZKSYNC"))
                .containsExactly("0x825d989f5258b61e8a5e7b1bc2b8fffbc57b8cc8");
        assertThat(definition.contractSet("LINEA"))
                .containsExactly("0x22e2f236065b780fa33ec8c4e58b99ebc8b55c57");
        assertThat(definition.contractSets()).hasSize(6);
    }

    @Test
    @DisplayName("enricher loads all 6 networks and fails fast when the resource is missing")
    void enricherLoadsFromConfig() {
        ProtocolResourceCatalog catalog = new ProtocolResourceLoader(new ObjectMapper());
        PancakeMasterChefEnricher enricher = new PancakeMasterChefEnricher(null, catalog);
        assertThat(enricher).isNotNull();

        ProtocolResourceCatalog empty = ProtocolResourceCatalog.empty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PancakeMasterChefEnricher(null, empty))
                .isInstanceOf(IllegalStateException.class);
    }
}
