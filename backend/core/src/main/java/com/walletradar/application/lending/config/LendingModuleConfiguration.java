package com.walletradar.application.lending.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LendingMarketRateProperties.class)
public class LendingModuleConfiguration {
}
