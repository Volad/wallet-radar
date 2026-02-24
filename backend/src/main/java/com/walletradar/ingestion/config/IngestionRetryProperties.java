package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RPC retry policy for ingestion (exponential backoff ± jitter). Documented in application.yml.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.retry")
@NoArgsConstructor
@Getter
@Setter
public class IngestionRetryProperties {

    /** Base delay in ms for first retry; doubles each attempt. Default 1000. */
    private long baseDelayMs = 1000L;

    /** Jitter factor 0..1 (e.g. 0.2 = ±20%). Default 0.2. */
    private double jitterFactor = 0.2;

    /** Max retry attempts (excluding initial call). Default 5. */
    private int maxAttempts = 5;
}
