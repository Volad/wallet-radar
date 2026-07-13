package com.walletradar.application.pricing.latest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the independent latest-price refresh subsystem.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LatestPriceProperties.class)
public class LatestPriceConfiguration {
}
