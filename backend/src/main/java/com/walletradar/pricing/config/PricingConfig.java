package com.walletradar.pricing.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.walletradar.common.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, Map<String, String>> coinsListBulkCache(
            PricingProperties pricingProperties) {
        int ttlHours = pricingProperties.getContractMapping().getCoinsListCacheTtlHours();
        return Caffeine.newBuilder()
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .maximumSize(1)
                .build();
    }
}
