package com.walletradar.ingestion.pipeline.classification.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenSymbolFallbackSupportTest {

    @Test
    void resolvesKnownEulerVaultContract() {
        assertThat(TokenSymbolFallbackSupport.resolve(
                "0x39de0f00189306062d79edec6dca5bb6bfd108f9",
                ""
        )).isEqualTo("eUSDC-2");
    }

    @Test
    void keepsNonBlankSymbol() {
        assertThat(TokenSymbolFallbackSupport.resolve(
                "0xabc",
                "USDC"
        )).isEqualTo("USDC");
    }

    @Test
    void fallsBackToContractSuffixWhenUnknown() {
        assertThat(TokenSymbolFallbackSupport.resolve(
                "0x1234567890abcdef1234567890abcdef12345678",
                null
        )).isEqualTo("ERC20:345678");
    }
}
