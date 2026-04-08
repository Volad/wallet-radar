package com.walletradar.pricing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalAssetCatalogTest {

    @Test
    void auditedAaveWethReceiptAliasesCollapseToEth() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aEthWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aArbWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aLinWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aManWETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aZksWETH")).isEqualTo("ETH");
    }
}
