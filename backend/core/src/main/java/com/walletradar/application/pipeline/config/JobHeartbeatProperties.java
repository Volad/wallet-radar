package com.walletradar.application.pipeline.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "walletradar.pipeline.jobs")
@NoArgsConstructor
@Getter
@Setter
public class JobHeartbeatProperties {

    private long heartbeatIntervalMs = 30_000L;
    private long resumeIntervalMs = 60_000L;

    public Duration heartbeatInterval() {
        return Duration.ofMillis(heartbeatIntervalMs);
    }

    public Duration resumeInterval() {
        return Duration.ofMillis(resumeIntervalMs);
    }
}
