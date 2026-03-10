package com.walletradar.pricing.resolver;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.pricing.config.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigOverrideContractResolverTest {

    private ConfigOverrideContractResolver resolver;

    @BeforeEach
    void setUp() {
        PricingProperties props = new PricingProperties();
        props.setContractToCoinGeckoId(Map.of(
                "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "weth",
                "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "usd-coin",
                "0x5979d7b546e38e414f7e9822514be443a4800529", "wrapped-steth",
                "0x3055913c90fcc1a6ce9a358911721eeb942013a1", "pancakeswap-token",
                "0x4200000000000000000000000000000000000006", "weth"));
        resolver = new ConfigOverrideContractResolver(props);
    }

    @Test
    @DisplayName("returns override for known contract")
    void knownContractReturnsOverride() {
        assertThat(resolver.resolve("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", NetworkId.ETHEREUM))
                .hasValue("weth");
        assertThat(resolver.resolve("0xA0b86991c6218b36c1d19D4a2e9eb0ce3606eb48", NetworkId.ARBITRUM))
                .hasValue("usd-coin");
    }

    @Test
    @DisplayName("returns empty for unknown contract")
    void unknownContractReturnsEmpty() {
        assertThat(resolver.resolve("0x0000000000000000000000000000000000000001", NetworkId.ETHEREUM))
                .isEmpty();
    }

    @Test
    @DisplayName("returns empty for null or blank contract")
    void nullOrBlankReturnsEmpty() {
        assertThat(resolver.resolve(null, NetworkId.ETHEREUM)).isEmpty();
        assertThat(resolver.resolve("", NetworkId.ETHEREUM)).isEmpty();
        assertThat(resolver.resolve("   ", NetworkId.ETHEREUM)).isEmpty();
    }

    @Test
    @DisplayName("normalizes contract to lowercase")
    void normalizesToLowercase() {
        assertThat(resolver.resolve("0xC02AAA39B223FE8D0A0E5C4F27EAD9083C756CC2", NetworkId.ETHEREUM))
                .hasValue("weth");
    }

    @Test
    @DisplayName("returns overrides for remaining legit residual market assets")
    void remainingLegitResidualAssetOverridesResolve() {
        assertThat(resolver.resolve("0x5979d7b546e38e414f7e9822514be443a4800529", NetworkId.ARBITRUM))
                .hasValue("wrapped-steth");
        assertThat(resolver.resolve("0x3055913c90fcc1a6ce9a358911721eeb942013a1", NetworkId.BASE))
                .hasValue("pancakeswap-token");
        assertThat(resolver.resolve("0x4200000000000000000000000000000000000006", NetworkId.BASE))
                .hasValue("weth");
    }
}
