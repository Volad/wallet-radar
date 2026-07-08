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
    public static final String PIPELINE_STAGE_EXECUTOR = "pipeline-stage-executor";
    public static final String PRICING_EXECUTOR = "pricing-executor";
    public static final String UNIVERSE_SYNC_PLAN_EXECUTOR = "universe-sync-plan-executor";

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

    @Bean(name = PIPELINE_STAGE_EXECUTOR)
    public Executor pipelineStageExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(16);
        e.setThreadNamePrefix("pipeline-stage-");
        e.initialize();
        return e;
    }

    @Bean(name = PRICING_EXECUTOR)
    public Executor pricingExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(16);
        e.setMaxPoolSize(16);
        e.setQueueCapacity(64);
        e.setThreadNamePrefix("pricing-");
        e.initialize();
        return e;
    }

    /** Runs {@link com.walletradar.session.application.AccountUniverseSyncPlannerService} off HTTP threads (RPC / explorer head). */
    @Bean(name = UNIVERSE_SYNC_PLAN_EXECUTOR)
    public Executor universeSyncPlanExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(256);
        e.setThreadNamePrefix("universe-plan-");
        e.initialize();
        return e;
    }
}
