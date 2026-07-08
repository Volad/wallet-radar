package com.walletradar.application.normalization.pipeline.descriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolDescriptorLoaderTest {

    private static ProtocolDescriptorService service;

    @BeforeAll
    static void loadDescriptors() {
        ProtocolDescriptorLoader loader = new ProtocolDescriptorLoader(new ObjectMapper());
        service = new ProtocolDescriptorService(loader);
    }

    @Test
    @DisplayName("Loads protocol descriptors and cross-validates against protocol-registry.json")
    void loadsAndValidatesDescriptors() {
        assertThat(service.all()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(service.find("Uniswap")).isPresent();
        assertThat(service.find("Aave")).isPresent();
        assertThat(service.find("GMX")).isPresent();
    }

    @Test
    @DisplayName("Capabilities are exposed per protocol")
    void capabilities() {
        assertThat(service.hasCapability("Aave", ProtocolCapability.LENDING)).isTrue();
        assertThat(service.hasCapability("Uniswap", ProtocolCapability.LP_PRESENTATION)).isTrue();
        assertThat(service.hasCapability("GMX", ProtocolCapability.VALUATION)).isTrue();
    }
}
