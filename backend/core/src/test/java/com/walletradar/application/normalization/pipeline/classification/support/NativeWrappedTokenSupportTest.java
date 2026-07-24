package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link NativeWrappedTokenSupport#canonicalWeth(NetworkId)} resolves the wrapped-native
 * contract from {@code network-descriptors.yml} (single source of truth) rather than a hardcoded
 * per-class map.
 */
class NativeWrappedTokenSupportTest {

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // Building the test registry binds NetworkNativeAssets (same convention as
        // NetworkStablecoinContracts). Without this the static bridge returns null defaults.
        NetworkTestFixtures.registry();
    }

    @Test
    void canonicalWeth_returnsYmlWrappedNativePerNetwork() {
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.ETHEREUM))
                .isEqualTo("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.ARBITRUM))
                .isEqualTo("0x82af49447d8a07e3bd95bd0d56f35241523fbab1");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.OPTIMISM))
                .isEqualTo("0x4200000000000000000000000000000000000006");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.BASE))
                .isEqualTo("0x4200000000000000000000000000000000000006");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.UNICHAIN))
                .isEqualTo("0x4200000000000000000000000000000000000006");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.ZKSYNC))
                .isEqualTo("0x5aea5775959fbc2557cc8789bc1bf90a239d9a91");
    }

    @Test
    void canonicalWeth_widenedCoverage_nonEthNativeChains() {
        // W1: coverage now spans every network with a configured wrapped-native, not just the old
        // ETH-only subset. The wrapped-native contract is the correct unwrap slot key on these chains.
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.LINEA))
                .isEqualTo("0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.KATANA))
                .isEqualTo("0xee7d8bcfb72bc1880d0cf19822eb0a2e6577ab62");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.BSC))
                .isEqualTo("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.POLYGON))
                .isEqualTo("0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.AVALANCHE))
                .isEqualTo("0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.MANTLE))
                .isEqualTo("0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8");
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.PLASMA))
                .isEqualTo("0x6100e367285b01f48d07953803a2d8dca5d19873");
    }

    @Test
    void canonicalWeth_resolvesConfiguredWrappedNativeAndNullOtherwise() {
        assertThat(NativeWrappedTokenSupport.canonicalWeth(null)).isNull();
        // Solana has a configured wrapped-native (wSOL SPL mint) in network-descriptors.yml.
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.SOLANA))
                .isEqualTo("so11111111111111111111111111111111111111112");
        // TON has no wrapped-native configured.
        assertThat(NativeWrappedTokenSupport.canonicalWeth(NetworkId.TON)).isNull();
    }
}
