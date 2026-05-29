package com.walletradar.accounting.support;

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
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aAvaSAVAX", null)).isEqualTo("FAMILY:AVAX");
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
    void mapsExtendedEthFamilyAliasesAcrossLayer1AndLayer2() {
        // Cycle/6 C1: extended family resolver entries so basis carry works on LENDING_DEPOSIT /
        // LENDING_WITHDRAW transfers for wrapped, staked, and lending-receipt ETH variants.
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aBasWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aOptWETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("cmETH", null)).isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("CMETH", null)).isEqualTo("FAMILY:ETH");
    }

    @Test
    void mapsExtendedStablecoinFamilyAliasesForLendingReceipts() {
        // USDC variants
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aZksUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("aOptUSDC", null)).isEqualTo("FAMILY:USDC");
        assertThat(AccountingAssetFamilySupport.continuityIdentity("fUSDC", null)).isEqualTo("FAMILY:USDC");
        // USDT variants
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
    void includesStakedEthVariantsInSpotEthTimelineRollup() {
        // P0-B: Full FAMILY:ETH included set: ETH, WETH, AMANWETH, CMETH, METH, WEETH, WSTETH, STETH, RSETH
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "ETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "CMETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "METH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WEETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "STETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "RSETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "WSTETH")).isTrue();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "AMANWETH")).isTrue();
        // BBSOL stays excluded from FAMILY:ETH (it maps to FAMILY:SOL)
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:ETH", "BBSOL")).isFalse();
        assertThat(AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation("FAMILY:BTC", "WBTC")).isTrue();
    }
}
