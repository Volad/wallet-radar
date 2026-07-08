package com.walletradar.platform.common.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "walletradar.async")
@NoArgsConstructor
@Getter
@Setter
public class AsyncExecutorProperties {

    private Pool backfillCoordinator = pool(1, 1, 0);
    private Pool backfill = pool(4, 18, 0);
    private Pool pipelineStage = pool(4, 4, 16);
    private Pool pricing = pool(16, 16, 64);
    private Pool universeSyncPlan = pool(2, 4, 256);

    private static Pool pool(int core, int max, int queue) {
        Pool p = new Pool();
        p.setCoreSize(core);
        p.setMaxSize(max);
        p.setQueueCapacity(queue);
        return p;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Pool {
        private int coreSize = 4;
        private int maxSize = 4;
        private int queueCapacity = 0;
    }
}
