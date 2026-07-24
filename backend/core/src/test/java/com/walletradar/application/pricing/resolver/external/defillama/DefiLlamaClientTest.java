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
        // w4plus: DefiLlama exposes Plasma under the "plasma" coins slug (verified WXPL price live).
        assertThat(DefiLlamaClient.chainSlug(NetworkId.PLASMA)).contains("plasma");
    }

    @Test
    void mapsKatanaAndUnichainSoC2EthDerivativesResolveIndependently() {
        // ADR-054 §6 / plan §7c: unlocks free DefiLlama-by-contract pricing for weETH/yvvbETH on
        // Katana and wstETH/weETH on Unichain.
        assertThat(DefiLlamaClient.chainSlug(NetworkId.KATANA)).contains("katana");
        assertThat(DefiLlamaClient.chainSlug(NetworkId.UNICHAIN)).contains("unichain");
    }

    @Test
    void mapsTonToDefiLlamaChainSlug() {
        // PR3 RC-T1.5: TON pricing re-enabled so native TON + jetton legs are priceable.
        assertThat(DefiLlamaClient.chainSlug(NetworkId.TON)).contains("ton");
    }

    @Test
    void returnsEmptyForNullNetwork() {
        assertThat(DefiLlamaClient.chainSlug(null)).isEmpty();
    }
}
