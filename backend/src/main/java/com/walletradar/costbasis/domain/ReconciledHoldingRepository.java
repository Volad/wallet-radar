package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for current holdings read model derived from replay state plus on-chain evidence.
 */
public interface ReconciledHoldingRepository extends MongoRepository<ReconciledHolding, String> {
}
