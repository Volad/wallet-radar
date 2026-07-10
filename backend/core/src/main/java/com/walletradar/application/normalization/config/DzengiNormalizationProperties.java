package com.walletradar.application.normalization.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the Dzengi normalization worker.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.dzengi")
@NoArgsConstructor
@Getter
@Setter
public class DzengiNormalizationProperties {

    private boolean enabled = true;
    private int batchSize = 250;
    private long scheduleIntervalMs = 90_000L;
}
