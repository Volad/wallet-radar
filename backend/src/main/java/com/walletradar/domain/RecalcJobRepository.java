package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for recalc_jobs. Used by override/recalc flow; AvcoEngine does not create jobs (T-015 scope).
 */
public interface RecalcJobRepository extends MongoRepository<RecalcJob, String> {
}
