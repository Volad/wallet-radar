package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the on-chain clarification worker.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.clarification")
@NoArgsConstructor
@Getter
@Setter
public class OnChainClarificationProperties {

    private boolean enabled = false;

    private int batchSize = 100;

    private long scheduleIntervalMs = 120_000;

    private long retryDelaySeconds = 120;

    private int maxAttempts = 3;
}
