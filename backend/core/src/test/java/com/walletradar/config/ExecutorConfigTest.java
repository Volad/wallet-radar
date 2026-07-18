package com.walletradar.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        PlatformExecutorConfiguration.class,
        AsyncConfig.class,
        SchedulerConfig.class
})
class ExecutorConfigTest {

    @Autowired
    @Qualifier(AsyncConfig.BACKFILL_COORDINATOR_EXECUTOR)
    Executor backfillCoordinatorExecutor;

    @Autowired
    @Qualifier(AsyncConfig.BACKFILL_EXECUTOR)
    Executor backfillExecutor;

    @Autowired
    @Qualifier(AsyncConfig.UNIVERSE_SYNC_PLAN_EXECUTOR)
    Executor universeSyncPlanExecutor;

    @Autowired
    @Qualifier(SchedulerConfig.SCHEDULER_POOL)
    ThreadPoolTaskScheduler schedulerPool;

    @Test
    @DisplayName("backfill async executors are created with expected pool sizes")
    void executorsCreated() {
        assertThat(backfillCoordinatorExecutor).isNotNull();
        assertThat(backfillExecutor).isNotNull();
        assertThat(universeSyncPlanExecutor).isNotNull();

        if (backfillCoordinatorExecutor instanceof ThreadPoolTaskExecutor coordinator) {
            assertThat(coordinator.getCorePoolSize()).isEqualTo(1);
            assertThat(coordinator.getMaxPoolSize()).isEqualTo(1);
        }

        if (backfillExecutor instanceof ThreadPoolTaskExecutor worker) {
            assertThat(worker.getCorePoolSize()).isEqualTo(4);
            assertThat(worker.getMaxPoolSize()).isEqualTo(18);
        }

        if (universeSyncPlanExecutor instanceof ThreadPoolTaskExecutor universe) {
            assertThat(universe.getCorePoolSize()).isEqualTo(2);
            assertThat(universe.getMaxPoolSize()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("scheduler pool is created and configured")
    void schedulerPoolCreated() {
        assertThat(schedulerPool).isNotNull();
        assertThat(schedulerPool.getThreadNamePrefix()).isEqualTo("scheduler-");
        assertThat(schedulerPool.getPoolSize()).isLessThanOrEqualTo(2);
    }
}
