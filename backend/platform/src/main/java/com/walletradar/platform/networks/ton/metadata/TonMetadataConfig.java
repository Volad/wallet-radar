package com.walletradar.platform.networks.ton.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the free-tier TON Center jetton-metadata client (WS-7) and its client-side request-rate gate.
 */
@Configuration
@EnableConfigurationProperties(TonMetadataProperties.class)
public class TonMetadataConfig {

    @Bean
    public TonMetadataRequestThrottle tonMetadataRequestThrottle(TonMetadataProperties tonMetadataProperties) {
        return new TonMetadataRequestThrottle(tonMetadataProperties.getMinRequestIntervalMs());
    }

    @Bean
    public TonMetadataClient tonMetadataClient(WebClient.Builder webClientBuilder,
                                               TonMetadataProperties tonMetadataProperties,
                                               TonMetadataRequestThrottle tonMetadataRequestThrottle,
                                               ObjectMapper objectMapper) {
        return new WebClientTonMetadataClient(webClientBuilder, tonMetadataProperties,
                tonMetadataRequestThrottle, objectMapper);
    }
}
