package com.walletradar.platform.common.config;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class AsyncConfig {

    public static final String BACKFILL_COORDINATOR_EXECUTOR = "backfill-coordinator-executor";
    public static final String BACKFILL_EXECUTOR = "backfill-executor";
    public static final String PIPELINE_STAGE_EXECUTOR = "pipeline-stage-executor";
    public static final String PRICING_EXECUTOR = "pricing-executor";
    public static final String UNIVERSE_SYNC_PLAN_EXECUTOR = "universe-sync-plan-executor";

    private final AsyncExecutorProperties asyncExecutorProperties;

    /** Single-thread executor for backfill coordinator so it does not consume a slot in the worker pool. */
    @Bean(name = BACKFILL_COORDINATOR_EXECUTOR)
    public Executor backfillCoordinatorExecutor() {
        return executor("backfill-coord-", asyncExecutorProperties.getBackfillCoordinator());
    }

    @Bean(name = BACKFILL_EXECUTOR)
    public Executor backfillExecutor() {
        return executor("backfill-", asyncExecutorProperties.getBackfill());
    }

    @Bean(name = PIPELINE_STAGE_EXECUTOR)
    public Executor pipelineStageExecutor() {
        return executor("pipeline-stage-", asyncExecutorProperties.getPipelineStage());
    }

    @Bean(name = PRICING_EXECUTOR)
    public Executor pricingExecutor() {
        return executor("pricing-", asyncExecutorProperties.getPricing());
    }

    /** Runs {@link com.walletradar.application.session.application.AccountUniverseSyncPlannerService} off HTTP threads (RPC / explorer head). */
    @Bean(name = UNIVERSE_SYNC_PLAN_EXECUTOR)
    public Executor universeSyncPlanExecutor() {
        return executor("universe-plan-", asyncExecutorProperties.getUniverseSyncPlan());
    }

    private static Executor executor(String threadPrefix, AsyncExecutorProperties.Pool pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        if (pool.getQueueCapacity() > 0) {
            executor.setQueueCapacity(pool.getQueueCapacity());
        }
        executor.setThreadNamePrefix(threadPrefix);
        executor.initialize();
        return executor;
    }
}
