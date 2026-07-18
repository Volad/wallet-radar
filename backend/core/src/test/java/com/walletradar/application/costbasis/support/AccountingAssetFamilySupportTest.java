package com.walletradar.application.costbasis.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingAssetFamilySupportTest {

    @Test
    void mapsWrappedBitcoinAndAaveWrappedBitcoinIntoBtcFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("BTC", null)).isEqualTo("FAMILY:BTC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WBTC", null)).isEqualTo("FAMILY:BTC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aArbWBTC", null)).isEqualTo("FAMILY:BTC");
    }

    @Test
    void mapsAaveWrappedAvaxAndMantleIntoCanonicalFamilies() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AVAX", null)).isEqualTo("FAMILY:AVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aAvaWAVAX", null)).isEqualTo("FAMILY:AVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aAvaSAVAX", null)).isEqualTo("FAMILY:SAVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("sAVAX", null)).isEqualTo("FAMILY:SAVAX");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("MNT", null)).isEqualTo("FAMILY:MNT");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WMNT", null)).isEqualTo("FAMILY:MNT");
    }

    @Test
    void mapsAaveReceiptSymbolsPerUnderlyingAcrossChains() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AAVAUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("ABASUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("AARBARB", null)).isEqualTo("FAMILY:ARB");
    }

    @Test
    void mapsUsdeAliasesIntoDedicatedFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USDE", null)).isEqualTo("FAMILY:USDE");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USDe", null)).isEqualTo("FAMILY:USDE");
        assertThat(AccountingAssetFamilySupport.continuityIdentity(
                "USDe",
                "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34"
        )).isEqualTo("FAMILY:USDE");
    }

    @Test
    void mapsC1EthIntoEthFamilyAndC2IntoOwnFamilies() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aBasWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("VBETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("cmETH", null)).isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("CMETH", null)).isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("WSTETH", null)).isEqualTo("FAMILY:WSTETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWETH-1", null)).isEqualTo("FAMILY:EWETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("EWEETH-1", null)).isEqualTo("FAMILY:EWEETH");
    }

    @Test
    void mapsExtendedStablecoinFamilyAliasesForLendingReceipts() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aZksUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aOptUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("fUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("fUSDT", null)).isEqualTo("FAMILY:USDT");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("vbUSDT", null)).isEqualTo("FAMILY:USDT");
    }

    @Test
    void mapsExtendedMantleFamilyAlias() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aManWMNT", null)).isEqualTo("FAMILY:MNT");
    }

    @Test
    void mapsLpReceiptSymbolsToDedicatedFamily() {
        assertThat(AccountingAssetFamilySupport.continuityIdentity(
                "LP-RECEIPT:arbitrum:pancakeswap:196975",
                null
        )).isEqualTo("FAMILY:LP_RECEIPT");
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("LP-RECEIPT:base:pancakeswap:477096")).isTrue();
        assertThat(AccountingAssetFamilySupport.isLpReceiptSymbol("ETH")).isFalse();
    }

    @Test
    void confusableLookalikeSymbolsNeverCollapseIntoCanonicalFamily() {
        String cyrillicUsdc = "U\u0405D\u0421";
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, null))
                .isNotEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, null))
                .isEqualTo("SYMBOL:" + cyrillicUsdc.toUpperCase());
        assertThat(AccountingAssetFamilySupport.continuityIdentity(cyrillicUsdc, "0xDEADBEEF"))
                .isEqualTo("0xdeadbeef");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("USD₮0", null)).isEqualTo("FAMILY:USDT");
    }

    @Test
    void ethFamilyTimelineIncludesOnlyC1Members() {
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "ETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "AMANWETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "CMETH")).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WSTETH")).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:METH", "CMETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:BTC", "WBTC")).isTrue();
    }
}
