package com.walletradar.platform.common.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "walletradar.scheduler")
@NoArgsConstructor
@Getter
@Setter
public class SchedulerExecutorProperties {

    private int poolSize = 4;
}
