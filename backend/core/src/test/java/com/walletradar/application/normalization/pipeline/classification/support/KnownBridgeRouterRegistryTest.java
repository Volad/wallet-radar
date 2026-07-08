package com.walletradar.application.normalization.pipeline.classification.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnownBridgeRouterRegistryTest {

    @Test
    void acrossV3SpokePoolIsKnownBridgeRouter() {
        assertThat(KnownBridgeRouterRegistry.isKnownBridgeRouter(
                "0x943e6e07a7e8e791dafc44083e54041d743c46e9"
        )).isTrue();
    }

    @Test
    void velodromeDistributorIsKnownRewardDistributor() {
        assertThat(KnownBridgeRouterRegistry.isKnownRewardDistributor(
                "0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad"
        )).isTrue();
    }

    @Test
    void touchesKnownBridgeRouterAcrossFlowCounterparties() {
        assertThat(KnownBridgeRouterRegistry.touchesKnownBridgeRouter(List.of(
                "0x943e6e07a7e8e791dafc44083e54041d743c46e9"
        ))).isTrue();
    }
}
