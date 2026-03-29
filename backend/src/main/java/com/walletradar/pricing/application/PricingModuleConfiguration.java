package com.walletradar.pricing.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers pricing-module configuration properties.
 */
@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingModuleConfiguration {
}
