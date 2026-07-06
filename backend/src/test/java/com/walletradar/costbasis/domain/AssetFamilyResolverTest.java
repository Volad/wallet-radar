package com.walletradar.costbasis.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetFamilyResolverTest {

    private final AssetFamilyResolver resolver = new AssetFamilyResolver();

    @Test
    void collapsesStableUsdMembers() {
        assertThat(resolver.resolveFamily("usdt")).isEqualTo("STABLE_USD");
        assertThat(resolver.resolveFamily("USDC")).isEqualTo("STABLE_USD");
        assertThat(resolver.resolveFamily("dai")).isEqualTo("STABLE_USD");
    }

    @Test
    void leavesNonStableSymbolsAsUppercaseFamily() {
        assertThat(resolver.resolveFamily("eth")).isEqualTo("ETH");
        assertThat(resolver.resolveFamily("MNT")).isEqualTo("MNT");
        assertThat(resolver.resolveFamily("DOGS")).isEqualTo("DOGS");
    }
}
