package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the new on-chain normalization worker.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.on-chain")
@NoArgsConstructor
@Getter
@Setter
public class OnChainNormalizationProperties {

    /**
     * Guardrail while classifier/pricing stages are still landing.
     */
    private boolean enabled = false;

    /**
     * Maximum number of due raw transactions loaded in one normalization loop.
     */
    private int batchSize = 250;

    /**
     * Fixed delay between scheduled normalization runs.
     */
    private long scheduleIntervalMs = 90_000;

    /**
     * Retry cooldown for raw documents that failed shell normalization.
     */
    private long retryDelaySeconds = 60;
}
