package com.walletradar.application.normalization.pipeline.classification.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GmxV2HandlerRegistryTest {

    private Set<String> configuredHandlers;

    @BeforeEach
    void bindFromConfig() {
        configuredHandlers = new ProtocolResourceLoader(new ObjectMapper())
                .find("GMX", "v2")
                .map(ProtocolResourceDefinition::handlerContractAddresses)
                .orElseThrow();
        GmxV2HandlerRegistry.bind(configuredHandlers::contains);
    }

    @Test
    @DisplayName("golden set: config carries all 24 handler/vault contracts")
    void goldenSetCount() {
        assertThat(configuredHandlers).hasSize(24);
    }

    @Test
    @DisplayName("current Arbitrum OrderHandler is recognized")
    void currentOrderHandler() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x63492B775e30a9E6b4b4761c12605EB9d071d5e9")).isTrue();
    }

    @Test
    @DisplayName("deprecated legacy OrderHandler is recognized")
    void deprecatedLegacyHandler() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0xe68caaacdf6439628dfd2fe624847602991a31eb")).isTrue();
    }

    @Test
    @DisplayName("deprecated v2.1 OrderHandler is recognized")
    void deprecatedV21Handler() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x352f684ab9e97a6321a13cf03a61316b681d9fd2")).isTrue();
    }

    @Test
    @DisplayName("OrderVault (older) is recognized")
    void olderOrderVault() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x1eea01a3592b8943737977b93ed24be7842d2427")).isTrue();
    }

    @Test
    @DisplayName("Avalanche WithdrawalHandler is recognized")
    void avalancheHandler() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x334237f7d75497a22b1443f44ddccf95e72904a0")).isTrue();
    }

    @Test
    @DisplayName("random address is NOT recognized")
    void randomAddress() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0xaaaa000000000000000000000000000000000001")).isFalse();
    }

    @Test
    @DisplayName("null and blank are handled safely")
    void nullAndBlank() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(null)).isFalse();
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler("")).isFalse();
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler("  ")).isFalse();
    }

    @Test
    @DisplayName("case-insensitive matching works")
    void caseInsensitive() {
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x63492b775e30a9e6b4b4761c12605eb9d071d5e9")).isTrue();
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x63492B775E30A9E6B4B4761C12605EB9D071D5E9")).isTrue();
    }

    @Test
    @DisplayName("null bind falls back to deny-all")
    void nullBindDeniesAll() {
        GmxV2HandlerRegistry.bind(null);
        assertThat(GmxV2HandlerRegistry.isKnownGmxV2Handler(
                "0x63492b775e30a9e6b4b4761c12605eb9d071d5e9")).isFalse();
    }
}
