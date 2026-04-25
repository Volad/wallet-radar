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

    @Test
    void auditedAaveAvaxReceiptAliasesCollapseToAvax() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("sAVAX")).isEqualTo("AVAX");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aAvaWAVAX")).isEqualTo("AVAX");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("aAvaSAVAX")).isEqualTo("AVAX");
    }

    @Test
    void auditedKatanaVaultReceiptsCollapseToEthForPricing() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("vbETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("yvvbETH")).isEqualTo("ETH");
    }

    @Test
    void wstEthUsesEthFamilyCoinGeckoFallback() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("wstETH")).contains("staked-ether");
    }

    @Test
    void wstEthUsesStEthAndEthExchangeFallbacks() {
        assertThat(CanonicalAssetCatalog.exchangeMarketSymbols("wstETH"))
                .containsExactly("WSTETH", "STETH", "ETH");
    }
}
