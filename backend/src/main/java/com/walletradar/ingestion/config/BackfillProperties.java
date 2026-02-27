package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backfill job config (T-009). Window in blocks; ~1 year on Ethereum (12s block) ≈ 2.63M blocks.
 * Worker count aligns with ADR-014 (queue + worker loops).
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.backfill")
@NoArgsConstructor
@Getter
@Setter
public class BackfillProperties {

    /**
     * Number of blocks to backfill from current block (per network). Default ~1 year Ethereum.
     */
    private long windowBlocks = 2_628_000;

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

    /** How often (ms) to run InternalTransferReclassifier when queue empty (ADR-021). Default 5 min. */
    private long reclassifyScheduleIntervalMs = 300_000;

    /** Number of parallel segments to split the block range into per network. Default 4. */
    private int parallelSegments = 4;

    /**
     * Maximum number of segment workers running concurrently per wallet×network backfill.
     * This caps real RPC pressure even when {@code parallelSegments} is high.
     */
    private int parallelSegmentWorkers = 4;

    /**
     * Minimum interval between persisted sync_status RUNNING progress updates.
     */
    private long progressUpdateIntervalMs = 2_000;

    /**
     * Segment considered stale if no updates longer than this threshold; stale RUNNING is reset to PENDING.
     */
    private long segmentStaleAfterMs = 180_000;
}
