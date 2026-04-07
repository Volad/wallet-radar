package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for latest observed on-chain balance evidence.
 */
public interface OnChainBalanceRepository extends MongoRepository<OnChainBalance, String> {
}
