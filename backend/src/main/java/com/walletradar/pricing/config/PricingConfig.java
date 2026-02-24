package com.walletradar.pricing.config;

import com.walletradar.common.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pricing module configuration: properties and shared beans (e.g. rate limiter for CoinGecko).
 */
@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingConfig {

    @Bean
    public RateLimiter coingeckoHistoricalRateLimiter(PricingProperties pricingProperties) {
        return new RateLimiter(pricingProperties.getCoingeckoHistoricalRequestsPerMinute());
    }
}
