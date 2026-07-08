package com.walletradar.session.config;

import com.walletradar.integration.config.BybitIntegrationProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        SessionSecretsProperties.class,
        BybitIntegrationProperties.class,
        IntegrationBackfillProperties.class
})
public class SessionSupportConfig {
}
