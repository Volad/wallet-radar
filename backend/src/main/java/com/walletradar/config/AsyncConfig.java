package com.walletradar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Named thread pools per 02-architecture: backfill-executor, recalc-executor, sync-executor.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String BACKFILL_EXECUTOR = "backfill-executor";
    public static final String RECALC_EXECUTOR = "recalc-executor";
    public static final String SYNC_EXECUTOR = "sync-executor";

    @Bean(name = BACKFILL_EXECUTOR)
    public Executor backfillExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(18);
        e.setThreadNamePrefix("backfill-");
        e.initialize();
        return e;
    }

    @Bean(name = RECALC_EXECUTOR)
    public Executor recalcExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(4);
        e.setThreadNamePrefix("recalc-");
        e.initialize();
        return e;
    }

    @Bean(name = SYNC_EXECUTOR)
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(3);
        e.setMaxPoolSize(3);
        e.setThreadNamePrefix("sync-");
        e.initialize();
        return e;
    }
}
