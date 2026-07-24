package com.walletradar.platform.networks.solana.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendClient;
import com.walletradar.platform.networks.solana.jupiter.lend.JupiterLendProperties;
import com.walletradar.platform.networks.solana.jupiter.lend.WebClientJupiterLendClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the free-tier Jupiter (Solana) client, the Jupiter Lend Borrow API client, and their
 * client-side request-rate gates.
 */
@Configuration
@EnableConfigurationProperties({JupiterProperties.class, JupiterLendProperties.class})
public class JupiterConfig {

    @Bean
    public JupiterRequestThrottle jupiterRequestThrottle(JupiterProperties jupiterProperties) {
        return new JupiterRequestThrottle(jupiterProperties.getMinRequestIntervalMs());
    }

    @Bean
    public JupiterClient jupiterClient(WebClient.Builder webClientBuilder,
                                       JupiterProperties jupiterProperties,
                                       JupiterRequestThrottle jupiterRequestThrottle,
                                       ObjectMapper objectMapper) {
        return new WebClientJupiterClient(webClientBuilder, jupiterProperties,
                jupiterRequestThrottle, objectMapper);
    }

    /** Dedicated throttle for Jupiter Lend Borrow API traffic (jup.ag), mirroring the pricing gate. */
    @Bean
    public JupiterRequestThrottle jupiterLendRequestThrottle(JupiterLendProperties jupiterLendProperties) {
        return new JupiterRequestThrottle(jupiterLendProperties.getMinRequestIntervalMs());
    }

    @Bean
    public JupiterLendClient jupiterLendClient(WebClient.Builder webClientBuilder,
                                               JupiterLendProperties jupiterLendProperties,
                                               @Qualifier("jupiterLendRequestThrottle") JupiterRequestThrottle jupiterLendRequestThrottle,
                                               ObjectMapper objectMapper) {
        return new WebClientJupiterLendClient(webClientBuilder, jupiterLendProperties,
                jupiterLendRequestThrottle, objectMapper);
    }
}
