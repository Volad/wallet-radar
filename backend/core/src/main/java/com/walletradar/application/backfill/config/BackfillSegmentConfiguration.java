package com.walletradar.application.backfill.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Segment execution tuning profile for backfill.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class BackfillSegmentConfiguration {

    public static final long DEFAULT_SEGMENT_STALE_AFTER_MS = 180_000L;
    public static final int DEFAULT_PARALLEL_SEGMENTS = 2;
    public static final int DEFAULT_PARALLEL_SEGMENT_WORKERS = 2;

    /**
     * Segment considered stale if no updates longer than this threshold; stale RUNNING is reset to PENDING.
     */
    private Long segmentStaleAfterMs;

    /**
     * Number of parallel segments to split the block range into per wallet×network backfill.
     */
    private Integer parallelSegments;

    /**
     * Max segment workers running concurrently for one wallet×network backfill.
     */
    private Integer parallelSegmentWorkers;

    public static BackfillSegmentConfiguration defaults() {
        return new BackfillSegmentConfiguration(
                DEFAULT_SEGMENT_STALE_AFTER_MS,
                DEFAULT_PARALLEL_SEGMENTS,
                DEFAULT_PARALLEL_SEGMENT_WORKERS
        );
    }
}
