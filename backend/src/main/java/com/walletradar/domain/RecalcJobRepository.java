package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Set;

/**
 * Persistence for recalc_jobs. Used by override/recalc flow; AvcoEngine does not create jobs (T-015 scope).
 */
public interface RecalcJobRepository extends MongoRepository<RecalcJob, String> {

    /** TTL cleanup: remove completed/failed jobs older than cutoff (e.g. 24h). */
    void deleteByStatusInAndCompletedAtBefore(Set<RecalcJob.RecalcStatus> statuses, Instant cutoff);
}
