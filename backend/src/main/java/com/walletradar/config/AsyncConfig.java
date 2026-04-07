package com.walletradar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Executors used by the remaining backfill runtime.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String BACKFILL_COORDINATOR_EXECUTOR = "backfill-coordinator-executor";
    public static final String BACKFILL_EXECUTOR = "backfill-executor";

    /** Single-thread executor for backfill coordinator so it does not consume a slot in the worker pool. */
    @Bean(name = BACKFILL_COORDINATOR_EXECUTOR)
    public Executor backfillCoordinatorExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setThreadNamePrefix("backfill-coord-");
        e.initialize();
        return e;
    }

    @Bean(name = BACKFILL_EXECUTOR)
    public Executor backfillExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(18);
        e.setThreadNamePrefix("backfill-");
        e.initialize();
        return e;
    }
}
