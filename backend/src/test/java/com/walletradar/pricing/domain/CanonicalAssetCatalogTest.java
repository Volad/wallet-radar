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
    void liquidStakingReceiptsPegToNativeForPricing() {
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("CMETH")).isEqualTo("ETH");
        assertThat(CanonicalAssetCatalog.canonicalMarketSymbol("BBSOL")).isEqualTo("SOL");
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

    @Test
    void wstUsrUsesResolvCoinGeckoMarketForHistoricalTotalValuation() {
        assertThat(CanonicalAssetCatalog.coinGeckoId("wstUSR")).contains("resolv-wstusr");
        assertThat(CanonicalAssetCatalog.coinGeckoId("USR")).contains("resolv-usr");
    }

    @Test
    void pricingSkippedLowCapSymbolsAreMarkedSkippedAndHaveNoCoinGeckoId() {
        // Cycle/9 S5: known low-cap / delisted symbols with no resolvable historical USD source.
        for (String symbol : new String[] {"PAWS", "AURA", "EUL", "AGLD", "WLKN", "CUDIS", "TON"}) {
            assertThat(CanonicalAssetCatalog.isPricingSkipped(symbol))
                    .as("expected %s to be pricing-skipped", symbol)
                    .isTrue();
            assertThat(CanonicalAssetCatalog.coinGeckoId(symbol))
                    .as("expected no CoinGecko id for %s", symbol)
                    .isEmpty();
        }
    }

    @Test
    void pricedSymbolsAreNotMarkedPricingSkipped() {
        assertThat(CanonicalAssetCatalog.isPricingSkipped("ETH")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("BTC")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("USDC")).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped(null)).isFalse();
        assertThat(CanonicalAssetCatalog.isPricingSkipped("")).isFalse();
    }
}
