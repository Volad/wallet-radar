package com.walletradar.application.session.config;

import com.walletradar.application.cex.config.BybitIntegrationProperties;
import com.walletradar.application.cex.config.IntegrationBackfillProperties;
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
