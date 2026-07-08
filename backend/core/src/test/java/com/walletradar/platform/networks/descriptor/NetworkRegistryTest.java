package com.walletradar.platform.networks.descriptor;

import com.walletradar.domain.common.NetworkId;
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
}
