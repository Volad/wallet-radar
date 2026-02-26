package com.walletradar.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-process caches per 02-architecture and ADR-005.
 */
@Configuration
@EnableCaching
public class CaffeineConfig {

    public static final String SPOT_PRICE_CACHE = "spotPriceCache";
    public static final String HISTORICAL_PRICE_CACHE = "historicalPriceCache";
    public static final String COINS_LIST_BULK_CACHE = "coinsListBulkCache";
    public static final String SNAPSHOT_CACHE = "snapshotCache";
    public static final String CROSS_WALLET_AVCO_CACHE = "crossWalletAvcoCache";
    public static final String TOKEN_META_CACHE = "tokenMetaCache";

    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(SPOT_PRICE_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .build());
        manager.registerCustomCache(HISTORICAL_PRICE_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(10_000)
                .build());
        manager.registerCustomCache(SNAPSHOT_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(200)
                .build());
        manager.registerCustomCache(CROSS_WALLET_AVCO_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(2_000)
                .build());
        manager.registerCustomCache(TOKEN_META_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(5_000)
                .build());
        return manager;
    }
}
