package com.walletradar.application.lending.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LendingAssetSymbolSupportTest {

    @Test
    void resolvesAaveNetworkReceiptTokensToUnderlyingAssets() {
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("aManUSDC")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("aArbWBTC")).isEqualTo("WBTC");
    }

    @Test
    void resolvesDebtTokensWithNetworkPrefixToUnderlyingAssets() {
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("variableDebtManUSDe")).isEqualTo("USDE");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("variableDebtAvaGHO")).isEqualTo("GHO");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("variableDebtArbUSDCn")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.isBorrowSymbol("variableDebtManUSDe")).isTrue();
    }

    @Test
    void canonicalizesLifecycleEquivalentAssets() {
        assertThat(LendingAssetSymbolSupport.lifecycleAsset("ETH")).isEqualTo("ETH");
        assertThat(LendingAssetSymbolSupport.lifecycleAsset("WETH")).isEqualTo("ETH");
        assertThat(LendingAssetSymbolSupport.lifecycleAsset("USD₮0")).isEqualTo("USDT");
        assertThat(LendingAssetSymbolSupport.lifecycleAsset("USDT0")).isEqualTo("USDT");
    }

    @Test
    void resolvesSingleLetterReceiptPrefixesForProtocolMarkets() {
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("cUSDC")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("fUSDT")).isEqualTo("USDT");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("eETH")).isEqualTo("ETH");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("soUSDC")).isEqualTo("USDC");
    }

    @Test
    void resolvesEulerIndexedSharesToUnderlyingAssets() {
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("eWBTC-1")).isEqualTo("WBTC");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("eUSDC-2")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.lifecycleAsset("eWETH-1")).isEqualTo("ETH");
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("eWBTC-1")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("EURC")).isFalse();
    }

    @Test
    void resolvesMorphoVaultSharesToUnderlyingAssets() {
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("gtUSDCc")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.underlyingSymbol("MCUSDC")).isEqualTo("USDC");
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("gtUSDCc")).isTrue();
    }

    @Test
    void distinguishesPositionTokensFromUnderlyingSpotBalances() {
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("AARBARB")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("AARBWBTC")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("VARIABLEDEBTARBUSDCN")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("fUSDC")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("soUSDC")).isTrue();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("syrupUSDC")).isFalse();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("USDC")).isFalse();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("WBTC")).isFalse();
        assertThat(LendingAssetSymbolSupport.isLendingPositionSymbol("ETH")).isFalse();
    }
}
