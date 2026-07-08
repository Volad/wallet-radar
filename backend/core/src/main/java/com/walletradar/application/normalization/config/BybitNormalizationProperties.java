package com.walletradar.application.normalization.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the Bybit normalization worker.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.bybit")
@NoArgsConstructor
@Getter
@Setter
public class BybitNormalizationProperties {

    private boolean enabled = false;

    private int batchSize = 250;

    private long scheduleIntervalMs = 90_000L;
}
