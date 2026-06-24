package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpoofTokenQuarantineSupportTest {

    // Confusable homoglyph tickers (regression anchors from the prod address-poisoning attack).
    private static final String CYRILLIC_USDC = "U\u0405D\u0421";      // UЅDС
    private static final String LISU_USDC = "\uA4F4\uA4E2\uA4D3\u0421"; // ꓴꓢꓓС
    private static final String COMBINING_USDC = "US\u1E0CC";          // USḌC
    private static final String REAL_USDT0 = "USD\u20AE0";              // USD₮0 (₮ = U+20AE, allow-listed)
    private static final String FAKE_CONTRACT = "0x000000000000000000000000000000000000dead";
    private static final String BASE_REAL_USDC_CONTRACT = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

    @Test
    @DisplayName("Cyrillic UЅDС on a non-canonical contract is a spoof")
    void cyrillicSpoof() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, FAKE_CONTRACT, CYRILLIC_USDC))
                .isTrue();
    }

    @Test
    @DisplayName("Lisu ꓴꓢꓓС on a non-canonical contract is a spoof")
    void lisuSpoof() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, FAKE_CONTRACT, LISU_USDC))
                .isTrue();
    }

    @Test
    @DisplayName("Combining-diacritic USḌC on a non-canonical contract is a spoof")
    void combiningSpoof() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, FAKE_CONTRACT, COMBINING_USDC))
                .isTrue();
    }

    @Test
    @DisplayName("Real ASCII USDC is never a spoof")
    void realUsdcNotSpoof() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, BASE_REAL_USDC_CONTRACT, "USDC"))
                .isFalse();
    }

    @Test
    @DisplayName("Real USD₮0 (whitelisted ₮ glyph) is never a spoof")
    void realUsdt0NotSpoof() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.OPTIMISM, FAKE_CONTRACT, REAL_USDT0))
                .isFalse();
    }

    @Test
    @DisplayName("A confusable symbol on a registry-known canonical contract is not quarantined")
    void canonicalContractGuard() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, BASE_REAL_USDC_CONTRACT, CYRILLIC_USDC))
                .isFalse();
    }

    @Test
    @DisplayName("Null/blank symbol is not a spoof")
    void nullSymbol() {
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, FAKE_CONTRACT, null)).isFalse();
        assertThat(SpoofTokenQuarantineSupport.isConfusableSpoofAsset(NetworkId.BASE, FAKE_CONTRACT, "USDT")).isFalse();
    }
}
