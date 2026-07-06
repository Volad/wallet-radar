package com.walletradar.liquiditypools.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LiquidityPoolsProperties.class)
public class LiquidityPoolsModuleConfiguration {
}
