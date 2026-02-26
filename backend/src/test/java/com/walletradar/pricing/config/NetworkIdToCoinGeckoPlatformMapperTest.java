package com.walletradar.pricing.config;

import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkIdToCoinGeckoPlatformMapperTest {

    @Test
    @DisplayName("maps all supported networks to platform IDs")
    void mapsAllNetworks() {
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.ETHEREUM)).hasValue("ethereum");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.ARBITRUM)).hasValue("arbitrum-one");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.OPTIMISM)).hasValue("optimism");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.POLYGON)).hasValue("polygon-pos");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.BASE)).hasValue("base");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.BSC)).hasValue("binance-smart-chain");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.AVALANCHE)).hasValue("avalanche");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.MANTLE)).hasValue("mantle");
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(NetworkId.SOLANA)).hasValue("solana");
    }

    @Test
    @DisplayName("returns empty for null networkId")
    void nullNetworkIdReturnsEmpty() {
        assertThat(NetworkIdToCoinGeckoPlatformMapper.toPlatformId(null)).isEmpty();
    }
}
