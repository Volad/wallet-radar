package com.walletradar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        CaffeineConfig.class,
        AsyncConfig.class,
        SchedulerConfig.class
})
class CacheAndExecutorConfigTest {

    @Autowired
    CacheManager cacheManager;

    @Autowired
    @Qualifier(AsyncConfig.BACKFILL_EXECUTOR)
    Executor backfillExecutor;

    @Autowired
    @Qualifier(AsyncConfig.RECALC_EXECUTOR)
    Executor recalcExecutor;

    @Autowired
    @Qualifier(AsyncConfig.SYNC_EXECUTOR)
    Executor syncExecutor;

    @Autowired
    @Qualifier(SchedulerConfig.SCHEDULER_POOL)
    ThreadPoolTaskScheduler schedulerPool;

    @Test
    @DisplayName("all 5 Caffeine caches are created and usable")
    void cachesCreatedAndUsed() {
        assertThat(cacheManager.getCache(CaffeineConfig.SPOT_PRICE_CACHE)).isNotNull();
        assertThat(cacheManager.getCache(CaffeineConfig.HISTORICAL_PRICE_CACHE)).isNotNull();
        assertThat(cacheManager.getCache(CaffeineConfig.SNAPSHOT_CACHE)).isNotNull();
        assertThat(cacheManager.getCache(CaffeineConfig.CROSS_WALLET_AVCO_CACHE)).isNotNull();
        assertThat(cacheManager.getCache(CaffeineConfig.TOKEN_META_CACHE)).isNotNull();

        cacheManager.getCache(CaffeineConfig.SPOT_PRICE_CACHE).put("key1", "value1");
        assertThat(cacheManager.getCache(CaffeineConfig.SPOT_PRICE_CACHE).get("key1").get()).isEqualTo("value1");
    }

    @Test
    @DisplayName("async executors are created with correct pool sizes")
    void executorsCreated() {
        assertThat(backfillExecutor).isNotNull();
        assertThat(recalcExecutor).isNotNull();
        assertThat(syncExecutor).isNotNull();
        if (backfillExecutor instanceof ThreadPoolTaskExecutor b) {
            assertThat(b.getCorePoolSize()).isEqualTo(4);
            assertThat(b.getMaxPoolSize()).isEqualTo(18);
        }
        if (recalcExecutor instanceof ThreadPoolTaskExecutor r) {
            assertThat(r.getCorePoolSize()).isEqualTo(4);
        }
        if (syncExecutor instanceof ThreadPoolTaskExecutor s) {
            assertThat(s.getCorePoolSize()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("scheduler pool is created and configured")
    void schedulerPoolCreated() {
        assertThat(schedulerPool).isNotNull();
        assertThat(schedulerPool.getThreadNamePrefix()).isEqualTo("scheduler-");
        // Pool size 2 is configured; current pool size may be 0 until tasks run
        assertThat(schedulerPool.getPoolSize()).isLessThanOrEqualTo(2);
    }
}
