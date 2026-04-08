package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.sync.BackfillSegment;

/**
 * Executes one persisted backfill segment. Segment planning and ownership stay
 * shared; only execution semantics differ per source/provider.
 */
public interface BackfillSegmentExecutor {

    boolean supports(BackfillSegment segment);

    void execute(BackfillSegment segment);
}
