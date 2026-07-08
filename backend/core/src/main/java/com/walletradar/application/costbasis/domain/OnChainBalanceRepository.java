package com.walletradar.application.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * Persistence for latest observed on-chain balance evidence.
 */
public interface OnChainBalanceRepository extends MongoRepository<OnChainBalance, String> {

    List<OnChainBalance> findBySessionId(String sessionId);

    List<OnChainBalance> findBySessionIdIn(Collection<String> sessionIds);

    void deleteAllBySessionId(String sessionId);
}
