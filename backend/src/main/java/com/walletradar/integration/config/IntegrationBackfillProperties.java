package com.walletradar.integration.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls external integration backfill scheduling and history window.
 */
@ConfigurationProperties(prefix = "walletradar.integration.backfill")
@Getter
@Setter
public class IntegrationBackfillProperties {

    private int historyYears = 2;
    private long pollIntervalMs = 15_000L;
}
