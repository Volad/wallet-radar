package com.walletradar.application.pricing.resolver.external.defillama;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefiLlamaClientTest {

    @Test
    void mapsCoreEvmNetworksToDefiLlamaChainSlugs() {
        assertThat(DefiLlamaClient.chainSlug(NetworkId.ETHEREUM)).contains("ethereum");
        assertThat(DefiLlamaClient.chainSlug(NetworkId.ARBITRUM)).contains("arbitrum");
        assertThat(DefiLlamaClient.chainSlug(NetworkId.MANTLE)).contains("mantle");
        assertThat(DefiLlamaClient.chainSlug(NetworkId.ZKSYNC)).contains("era");
    }

    @Test
    void mapsKatanaAndUnichainSoC2EthDerivativesResolveIndependently() {
        // ADR-054 §6 / plan §7c: unlocks free DefiLlama-by-contract pricing for weETH/yvvbETH on
        // Katana and wstETH/weETH on Unichain.
        assertThat(DefiLlamaClient.chainSlug(NetworkId.KATANA)).contains("katana");
        assertThat(DefiLlamaClient.chainSlug(NetworkId.UNICHAIN)).contains("unichain");
    }

    @Test
    void returnsEmptyForNullOrUnsupportedNetwork() {
        assertThat(DefiLlamaClient.chainSlug(null)).isEmpty();
        assertThat(DefiLlamaClient.chainSlug(NetworkId.TON)).isEmpty();
    }
}
