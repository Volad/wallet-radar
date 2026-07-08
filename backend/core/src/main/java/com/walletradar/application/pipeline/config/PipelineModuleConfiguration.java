package com.walletradar.application.pipeline.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JobHeartbeatProperties.class)
public class PipelineModuleConfiguration {
}
