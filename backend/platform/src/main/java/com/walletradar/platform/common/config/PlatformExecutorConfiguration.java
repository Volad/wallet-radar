package com.walletradar.platform.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AsyncExecutorProperties.class,
        SchedulerExecutorProperties.class
})
public class PlatformExecutorConfiguration {
}
