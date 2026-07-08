package com.walletradar.application.costbasis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers cost-basis module configuration properties.
 */
@Configuration
@EnableConfigurationProperties({
        CostBasisProperties.class,
        NativePoolReconciliationProperties.class,
        ReplayToleranceProperties.class
})
public class CostBasisModuleConfiguration {
}
