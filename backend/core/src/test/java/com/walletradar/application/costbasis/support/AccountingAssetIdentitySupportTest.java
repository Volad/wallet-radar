package com.walletradar.application.costbasis.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link AccountingAssetIdentitySupport#positionAssetIdentity} native-alias collapsing now
 * reads wrapped-native / native-alias contracts from {@code network-descriptors.yml} via the
 * {@link com.walletradar.domain.common.NetworkNativeAssets} bridge (W1 consolidation) instead of a
 * hardcoded per-class map.
 */
class AccountingAssetIdentitySupportTest {

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // Building the test registry binds NetworkNativeAssets (same convention as
        // NetworkStablecoinContracts). Without this the static bridge returns empty defaults.
        NetworkTestFixtures.registry();
    }

    @Test
    void wrappedNativeContract_collapsesToNativeIdentity_perNetwork() {
        assertNative(NetworkId.ETHEREUM, "WETH", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        assertNative(NetworkId.ARBITRUM, "WETH", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1");
        assertNative(NetworkId.OPTIMISM, "WETH", "0x4200000000000000000000000000000000000006");
        assertNative(NetworkId.BASE, "WETH", "0x4200000000000000000000000000000000000006");
        assertNative(NetworkId.UNICHAIN, "WETH", "0x4200000000000000000000000000000000000006");
        assertNative(NetworkId.LINEA, "WETH", "0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f");
        assertNative(NetworkId.BSC, "WBNB", "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
        assertNative(NetworkId.POLYGON, "WMATIC", "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270");
        assertNative(NetworkId.AVALANCHE, "WAVAX", "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7");
        assertNative(NetworkId.MANTLE, "WMNT", "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8");
    }

    @Test
    void zksync_bothNativeAliasContractsCollapseToNative() {
        // zkSync has an explicit native-alias contract (system contract 0x…800a) in addition to the
        // wrapped-native WETH — both must collapse to the native identity.
        assertNative(NetworkId.ZKSYNC, "WETH", "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91");
        assertNative(NetworkId.ZKSYNC, "ETH", "0x000000000000000000000000000000000000800a");
    }

    @Test
    void katanaAndPlasmaWrappedNative_nowCollapseToNative_w1Expansion() {
        // W1 intentional, economically-correct expansion: the old hardcoded map omitted these, so
        // wrap/unwrap of the network's wrapped-native was NOT treated as a same-asset move. The yml
        // is authoritative, so they now collapse to the native identity.
        assertNative(NetworkId.KATANA, "WETH", "0xee7d8bcfb72bc1880d0cf19822eb0a2e6577ab62");
        assertNative(NetworkId.PLASMA, "WXPL", "0x6100e367285b01f48d07953803a2d8dca5d19873");
    }

    @Test
    void nativeSymbolWithoutContract_collapsesToNative() {
        assertThat(AccountingAssetIdentitySupport.positionAssetIdentity(NetworkId.ETHEREUM, "ETH", null))
                .isEqualTo("NATIVE:ETHEREUM");
        assertThat(AccountingAssetIdentitySupport.positionAssetIdentity(NetworkId.BSC, "BNB", null))
                .isEqualTo("NATIVE:BSC");
        assertThat(AccountingAssetIdentitySupport.positionAssetIdentity(NetworkId.PLASMA, "XPL", null))
                .isEqualTo("NATIVE:PLASMA");
    }

    @Test
    void nonAliasContract_keepsContractIdentity() {
        // A regular ERC-20 (USDC on Ethereum) is not a native alias → identity is the contract.
        String usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
        assertThat(AccountingAssetIdentitySupport.positionAssetIdentity(NetworkId.ETHEREUM, "USDC", usdc))
                .isEqualTo(usdc);
    }

    private static void assertNative(NetworkId networkId, String symbol, String contract) {
        assertThat(AccountingAssetIdentitySupport.positionAssetIdentity(networkId, symbol, contract))
                .isEqualTo("NATIVE:" + networkId.name());
    }
}
