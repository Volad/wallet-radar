package com.walletradar.platform.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler pool for backfill retry and coordination jobs.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulerConfig {

    public static final String SCHEDULER_POOL = "scheduler-pool";

    private final SchedulerExecutorProperties schedulerExecutorProperties;

    @Bean(name = SCHEDULER_POOL)
    public ThreadPoolTaskScheduler schedulerPool() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(schedulerExecutorProperties.getPoolSize());
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
