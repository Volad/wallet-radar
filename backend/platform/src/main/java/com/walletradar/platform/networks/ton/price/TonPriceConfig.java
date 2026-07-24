package com.walletradar.platform.networks.ton.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the free-tier STON.fi (TON) price client and its client-side request-rate gate.
 */
@Configuration
@EnableConfigurationProperties(TonPriceProperties.class)
public class TonPriceConfig {

    @Bean
    public TonPriceRequestThrottle tonPriceRequestThrottle(TonPriceProperties tonPriceProperties) {
        return new TonPriceRequestThrottle(tonPriceProperties.getMinRequestIntervalMs());
    }

    @Bean
    public TonPriceClient tonPriceClient(WebClient.Builder webClientBuilder,
                                         TonPriceProperties tonPriceProperties,
                                         TonPriceRequestThrottle tonPriceRequestThrottle,
                                         ObjectMapper objectMapper) {
        return new WebClientTonPriceClient(webClientBuilder, tonPriceProperties,
                tonPriceRequestThrottle, objectMapper);
    }
}
