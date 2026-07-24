package com.walletradar.platform.networks.descriptor;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.NetworkNativeAssets;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkRegistryTest {

    private final NetworkRegistry registry = NetworkTestFixtures.registry();

    @Test
    @DisplayName("Loads native and wrapped-native metadata from walletradar.networks")
    void nativeAndWrappedNativeMetadata() {
        assertThat(registry.nativeSymbol(NetworkId.ETHEREUM)).isEqualTo("ETH");
        assertThat(registry.wrappedNativeContract(NetworkId.ETHEREUM))
                .isEqualTo("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        assertThat(registry.wrappedNativeSymbol(NetworkId.BSC)).isEqualTo("WBNB");
        assertThat(registry.isNativeAliasContract(
                NetworkId.ZKSYNC,
                "0x000000000000000000000000000000000000800a"
        )).isTrue();
    }

    @Test
    @DisplayName("Wallet support sets match legacy AddressValidator policy")
    void walletSupportSets() {
        assertThat(registry.walletSupportedNetworks()).contains(NetworkId.SOLANA, NetworkId.TON);
        assertThat(registry.evmWalletSupportedNetworks()).contains(NetworkId.ARBITRUM, NetworkId.PLASMA);
        assertThat(registry.evmWalletSupportedNetworks()).doesNotContain(NetworkId.SOLANA, NetworkId.TON);
    }

    @Test
    @DisplayName("USD stable contracts are exposed for pricing catalog lookups")
    void usdStableContracts() {
        assertThat(registry.usdStableContracts(NetworkId.BASE))
                .contains("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
    }

    @Test
    @DisplayName("NetworkNativeAssets bridge exposes wrapped-native ∪ native-alias union from yml")
    void networkNativeAssetsUnion() {
        // Constructing the registry binds NetworkNativeAssets. zkSync's union is the wrapped-native
        // WETH plus the explicit native-alias system contract.
        assertThat(NetworkNativeAssets.nativeAliasContracts(NetworkId.ZKSYNC))
                .containsExactlyInAnyOrder(
                        "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91",
                        "0x000000000000000000000000000000000000800a");
        // Single wrapped-native, no extra alias.
        assertThat(NetworkNativeAssets.nativeAliasContracts(NetworkId.ETHEREUM))
                .containsExactly("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2");
        // W1 expansions present in the union.
        assertThat(NetworkNativeAssets.nativeAliasContracts(NetworkId.KATANA))
                .containsExactly("0xee7d8bcfb72bc1880d0cf19822eb0a2e6577ab62");
        assertThat(NetworkNativeAssets.nativeAliasContracts(NetworkId.PLASMA))
                .containsExactly("0x6100e367285b01f48d07953803a2d8dca5d19873");
        assertThat(NetworkNativeAssets.nativeSymbol(NetworkId.ETHEREUM)).isEqualTo("ETH");
        assertThat(NetworkNativeAssets.wrappedNativeContract(NetworkId.PLASMA))
                .isEqualTo("0x6100e367285b01f48d07953803a2d8dca5d19873");
    }

    @Test
    @DisplayName("W14: nativeIdentity and nativeDecimals are exposed for SOLANA and TON; absent for EVM")
    void nativeIdentityAndDecimalsFromDescriptor() {
        // SOLANA: native-identity = NATIVE:SOLANA, native-decimals = 9
        assertThat(NetworkNativeAssets.nativeIdentity(NetworkId.SOLANA)).isEqualTo("NATIVE:SOLANA");
        assertThat(NetworkNativeAssets.nativeDecimals(NetworkId.SOLANA)).isEqualTo(9);
        assertThat(NetworkNativeAssets.nativeSymbol(NetworkId.SOLANA)).isEqualTo("SOL");

        // TON: native-identity = TONCOIN, native-decimals = 9
        assertThat(NetworkNativeAssets.nativeIdentity(NetworkId.TON)).isEqualTo("TONCOIN");
        assertThat(NetworkNativeAssets.nativeDecimals(NetworkId.TON)).isEqualTo(9);
        assertThat(NetworkNativeAssets.nativeSymbol(NetworkId.TON)).isEqualTo("TON");

        // EVM: native-identity absent (null), native-decimals present (18)
        assertThat(NetworkNativeAssets.nativeIdentity(NetworkId.ETHEREUM)).isNull();
        assertThat(NetworkNativeAssets.nativeDecimals(NetworkId.ETHEREUM)).isEqualTo(18);
        assertThat(NetworkNativeAssets.nativeDecimals(NetworkId.ARBITRUM)).isEqualTo(18);
        assertThat(NetworkNativeAssets.nativeDecimals(NetworkId.BSC)).isEqualTo(18);
    }

    @Test
    @DisplayName("ETH-family equivalent contracts derive from descriptors (W11): ETH-native "
            + "wrapped/alias + explicit bridged WETH; excludes non-ETH wrapped-natives")
    void ethFamilyEquivalentContracts() {
        assertThat(registry.ethFamilyEquivalentContracts()).containsExactlyInAnyOrder(
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", // WETH — Ethereum
                "0x82af49447d8a07e3bd95bd0d56f35241523fbab1", // WETH — Arbitrum
                "0x4200000000000000000000000000000000000006", // WETH — Base / Optimism / Unichain (OP-stack)
                "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91", // WETH — zkSync
                "0x000000000000000000000000000000000000800a", // native-ETH proxy — zkSync
                "0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f", // WETH — Linea
                "0xee7d8bcfb72bc1880d0cf19822eb0a2e6577ab62", // WETH/vbETH — Katana (fixed drift: was missing)
                "0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111", // bridged WETH — Mantle (eth-family-contracts)
                "0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab"  // bridged WETH.e — Avalanche (eth-family-contracts)
        );
        // Non-ETH wrapped-natives must never be allowlisted as ETH-family.
        assertThat(registry.ethFamilyEquivalentContracts())
                .doesNotContain(
                        "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", // WBNB
                        "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", // WAVAX
                        "0x78c1b0c915c4faa5fffa6cabf0219da63d7f4cb8", // WMNT
                        "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270", // WMATIC
                        "0x2def4285787d58a2f811af24755a8150622f4361"  // Cronos zkEVM WETH (dropped: unsupported network)
                );
    }
}
