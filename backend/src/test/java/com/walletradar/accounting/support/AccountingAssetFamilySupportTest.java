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
}
