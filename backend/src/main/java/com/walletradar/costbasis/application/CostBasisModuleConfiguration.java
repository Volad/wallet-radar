package com.walletradar.costbasis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers cost-basis module configuration properties.
 */
@Configuration
@EnableConfigurationProperties({
        CostBasisProperties.class,
        NativePoolReconciliationProperties.class
})
public class CostBasisModuleConfiguration {
}
