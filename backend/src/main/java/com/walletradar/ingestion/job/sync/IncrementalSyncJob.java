package com.walletradar.ingestion.job.sync;

import com.walletradar.ingestion.job.classification.ClassificationProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Incremental sync: fetches new blocks since last sync for each wallet×network.
 *
 * <p><b>TODO (T-010):</b> IncrementalSyncJob is not yet implemented. When implemented, it must use the split flow:
 * <ul>
 *   <li>Phase 1: {@link com.walletradar.ingestion.job.backfill.RawFetchSegmentProcessor} — fetch new blocks → store in raw_transactions</li>
 *   <li>Phase 2: {@link ClassificationProcessor} — read raw_transactions → classify → upsert normalized_transactions</li>
 * </ul>
 * Same as backfill (ADR-020); no RPC during classification.
 */
@Component
@Slf4j
public class IncrementalSyncJob {

    // @Scheduled(fixedDelay = 3_600_000) — uncomment when implemented
    public void run() {
        long startedAt = System.currentTimeMillis();
        log.info("IncrementalSyncJob started");
        try {
            log.debug("IncrementalSyncJob: not yet implemented (T-010)");
            log.info("IncrementalSyncJob finished: durationMs={}", System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("IncrementalSyncJob failed: durationMs={}", System.currentTimeMillis() - startedAt, e);
            throw e;
        }
    }
}
