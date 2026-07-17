package com.walletradar.application.normalization.pipeline.classification.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenMetadataRegistryTest {

    @Test
    @DisplayName("golden set: fallbackTokens resolve to the exact pre-config symbol/decimals/override")
    void fallbackGolden() {
        assertThat(TokenMetadataRegistry.fallbackSymbol("0x39de0f00189306062d79edec6dca5bb6bfd108f9"))
                .isEqualTo("eUSDC-2");
        assertThat(TokenMetadataRegistry.fallbackDecimals("0x39de0f00189306062d79edec6dca5bb6bfd108f9"))
                .isEqualTo(6);

        assertThat(TokenMetadataRegistry.fallbackSymbol("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"))
                .isEqualTo("USDC");
        assertThat(TokenMetadataRegistry.fallbackDecimals("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"))
                .isEqualTo(6);

        assertThat(TokenMetadataRegistry.fallbackSymbol("0x4200000000000000000000000000000000000006"))
                .isEqualTo("WETH");
        assertThat(TokenMetadataRegistry.fallbackDecimals("0x4200000000000000000000000000000000000006"))
                .isEqualTo(18);

        assertThat(TokenMetadataRegistry.fallbackSymbol("0x2514a2ce842705ead703d02fabfd8250bfcfb8bd"))
                .isEqualTo("soUSDC");
        assertThat(TokenMetadataRegistry.decimalOverride("0x2514a2ce842705ead703d02fabfd8250bfcfb8bd"))
                .isEqualTo(12);
    }

    @Test
    @DisplayName("fallbackTokens without an override do not expose one, and vice versa")
    void fallbackFieldSeparation() {
        assertThat(TokenMetadataRegistry.decimalOverride("0x4200000000000000000000000000000000000006")).isNull();
        assertThat(TokenMetadataRegistry.fallbackDecimals("0x2514a2ce842705ead703d02fabfd8250bfcfb8bd")).isNull();
    }

    @Test
    @DisplayName("golden set: builderTokens resolve to the exact pre-config symbol/decimals")
    void builderGolden() {
        assertThat(TokenMetadataRegistry.builderSymbol("0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb"))
                .isEqualTo("USDT0");
        assertThat(TokenMetadataRegistry.builderDecimals("0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb"))
                .isEqualTo(6);

        assertThat(TokenMetadataRegistry.builderSymbol("0x2a52b289ba68bbd02676640aa9f605700c9e5699"))
                .isEqualTo("wstUSR");
        assertThat(TokenMetadataRegistry.builderDecimals("0x2a52b289ba68bbd02676640aa9f605700c9e5699"))
                .isEqualTo(18);

        assertThat(TokenMetadataRegistry.builderSymbol("0xaf88d065e77c8cc2239327c5edb3a432268e5831"))
                .isEqualTo("USDC");
        assertThat(TokenMetadataRegistry.builderDecimals("0xaf88d065e77c8cc2239327c5edb3a432268e5831"))
                .isEqualTo(6);

        assertThat(TokenMetadataRegistry.builderSymbol("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8"))
                .isEqualTo("USDC");
        assertThat(TokenMetadataRegistry.builderDecimals("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8"))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("groups stay isolated: a builder-only contract is not visible via fallback lookups and vice versa")
    void groupsAreIsolated() {
        assertThat(TokenMetadataRegistry.fallbackSymbol("0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb")).isNull();
        assertThat(TokenMetadataRegistry.builderSymbol("0x39de0f00189306062d79edec6dca5bb6bfd108f9")).isNull();
    }

    @Test
    @DisplayName("null / blank / unknown / mixed-case inputs behave correctly")
    void edgeCases() {
        assertThat(TokenMetadataRegistry.fallbackSymbol(null)).isNull();
        assertThat(TokenMetadataRegistry.fallbackDecimals(" ")).isNull();
        assertThat(TokenMetadataRegistry.builderSymbol("0xdeadbeef")).isNull();
        assertThat(TokenMetadataRegistry.fallbackSymbol("0x39DE0F00189306062D79EDEC6DCA5BB6BFD108F9"))
                .isEqualTo("eUSDC-2");
    }
}
