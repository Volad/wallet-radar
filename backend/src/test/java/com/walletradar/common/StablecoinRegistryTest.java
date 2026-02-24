package com.walletradar.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StablecoinRegistryTest {

    private StablecoinRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StablecoinRegistry();
    }

    @Test
    @DisplayName("USDC Ethereum is stablecoin")
    void usdcEthereum() {
        assertThat(registry.isStablecoin("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")).isTrue();
        assertThat(registry.isStablecoin("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")).isTrue();
    }

    @Test
    @DisplayName("USDT, DAI, GHO, USDe, FRAX are stablecoins")
    void otherStablecoins() {
        assertThat(registry.isStablecoin("0xdac17f958d2ee523a2206206994597c13d831ec7")).isTrue();
        assertThat(registry.isStablecoin("0x6b175474e89094c44da98b954eedeac495271d0f")).isTrue();
        assertThat(registry.isStablecoin("0x40d16fc9686d086299136be70377984c4e2e770a")).isTrue();
        assertThat(registry.isStablecoin("0x4c9edd5852cd905f086c759e8383e09bff1e68b3")).isTrue();
        assertThat(registry.isStablecoin("0x853d955acef822db058eb8505911ed77f175b99e")).isTrue();
    }

    @Test
    @DisplayName("unknown contract is not stablecoin")
    void unknownContract() {
        assertThat(registry.isStablecoin("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")).isFalse();
        assertThat(registry.isStablecoin("0x0000000000000000000000000000000000000000")).isFalse();
    }

    @Test
    @DisplayName("null or blank returns false")
    void nullOrBlank() {
        assertThat(registry.isStablecoin(null)).isFalse();
        assertThat(registry.isStablecoin("")).isFalse();
        assertThat(registry.isStablecoin("   ")).isFalse();
    }
}
