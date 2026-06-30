package com.walletradar.lending.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LendingMarketRatePropertiesTest {

    @Test
    void healthFetchEnabledWhenAaveV3PoolConfigured() {
        LendingMarketRateProperties properties = new LendingMarketRateProperties();
        LendingMarketRateProperties.AaveV3NetworkConfig config = new LendingMarketRateProperties.AaveV3NetworkConfig();
        config.setEnabled(true);
        config.setPoolAddress("0x794a61358d6845594f94dc1db02a252b5b4814ad");
        properties.setAaveV3(Map.of("ARBITRUM", config));

        assertTrue(properties.isAaveV3HealthFetchEnabled("ARBITRUM"));
        assertTrue(properties.isAaveV3HealthFetchEnabled("arbitrum"));
        assertFalse(properties.isAaveV3HealthFetchEnabled("ETHEREUM"));
    }

    @Test
    void healthFetchDisabledWhenPoolMissingOrNetworkDisabled() {
        LendingMarketRateProperties properties = new LendingMarketRateProperties();
        LendingMarketRateProperties.AaveV3NetworkConfig disabled = new LendingMarketRateProperties.AaveV3NetworkConfig();
        disabled.setEnabled(false);
        disabled.setPoolAddress("0xpool");
        properties.setAaveV3(Map.of("BASE", disabled));

        assertFalse(properties.isAaveV3HealthFetchEnabled("BASE"));

        LendingMarketRateProperties.AaveV3NetworkConfig noPool = new LendingMarketRateProperties.AaveV3NetworkConfig();
        noPool.setEnabled(true);
        properties.setAaveV3(Map.of("MANTLE", noPool));
        assertFalse(properties.isAaveV3HealthFetchEnabled("MANTLE"));
    }
}
