package com.walletradar.application.pricing.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers pricing-module configuration properties.
 */
@Configuration
@EnableConfigurationProperties({
        PricingProperties.class,
        ExternalPricingEndpointProperties.class
})
public class PricingModuleConfiguration {
}
