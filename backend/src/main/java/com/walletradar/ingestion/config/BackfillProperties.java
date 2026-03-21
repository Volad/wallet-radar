package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backfill runtime settings.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.backfill")
@NoArgsConstructor
@Getter
@Setter
public class BackfillProperties {

    /**
     * Default block window used when a network override is not configured.
     */
    private long windowBlocks = 5_500_000;

    /**
     * Number of worker loops that drain the backfill queue. Default 4 (match backfill-executor core size).
     */
    private int workerThreads = 4;

    /** Max retry attempts for a failed network backfill before marking ABANDONED. */
    private int maxRetries = 5;

    /** Base delay in minutes for exponential backoff between retries. */
    private long retryBaseDelayMinutes = 2;

    /** Maximum delay in minutes (backoff ceiling). */
    private long retryMaxDelayMinutes = 60;

    /** How often (ms) the retry scheduler polls for FAILED items. */
    private long retrySchedulerIntervalMs = 120_000;

    /** Segment execution profiles (defaults + optional by-rpc overrides). */
    private BackfillSegmentsConfiguration segments = new BackfillSegmentsConfiguration();

    /**
     * Minimum interval between persisted sync_status RUNNING progress updates.
     */
    private long progressUpdateIntervalMs = 2_000;
}
