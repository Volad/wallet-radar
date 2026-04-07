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
}
