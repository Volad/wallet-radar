package com.walletradar.ingestion.job.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Incremental sync: fetches new blocks since last sync for each wallet×network.
 *
 * <p><b>TODO (T-010):</b> IncrementalSyncJob is not yet implemented. When implemented, it must use the split flow:
 * <ul>
 *   <li>Phase 1: {@link com.walletradar.ingestion.job.backfill.RawFetchSegmentProcessor} — fetch new blocks → store in raw_transactions</li>
 *   <li>Phase 2: {@link com.walletradar.ingestion.pipeline.classification.ClassificationProcessor} — read raw_transactions → classify → upsert economic_events</li>
 * </ul>
 * Same as backfill (ADR-020); no RPC during classification.
 */
@Component
@Slf4j
public class IncrementalSyncJob {

    // @Scheduled(fixedDelay = 3_600_000) — uncomment when implemented
    public void run() {
        log.debug("IncrementalSyncJob: not yet implemented (T-010)");
    }
}
