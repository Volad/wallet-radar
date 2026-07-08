package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for latest observed on-chain balance evidence.
 */
public interface OnChainBalanceRepository extends MongoRepository<OnChainBalance, String> {

    List<OnChainBalance> findBySessionId(String sessionId);

    void deleteAllBySessionId(String sessionId);
}
