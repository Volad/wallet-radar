package com.walletradar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler pool (2 threads) for @Scheduled jobs: IncrementalSync, SnapshotCron, CurrentBalancePoll.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    public static final String SCHEDULER_POOL = "scheduler-pool";

    @Bean(name = SCHEDULER_POOL)
    public ThreadPoolTaskScheduler schedulerPool() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("scheduler-");
        s.initialize();
        return s;
    }
}
