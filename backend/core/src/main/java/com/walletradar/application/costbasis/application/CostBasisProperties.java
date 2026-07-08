package com.walletradar.application.costbasis.application;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for stat validation and AVCO replay.
 */
@ConfigurationProperties(prefix = "walletradar.costbasis")
@NoArgsConstructor
@Getter
@Setter
public class CostBasisProperties {

    private boolean enabled = false;

    private int validationBatchSize = 250;

    private long scheduleIntervalMs = 120_000L;

    private long retryDelaySeconds = 120L;
}
