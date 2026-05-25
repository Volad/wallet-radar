package com.walletradar.ingestion.pipeline.classification.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GmxV2HandlerRegistryTest {

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
}
