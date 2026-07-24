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

    /**
     * Global (session-less) balance rows written by the scheduled refresh (id prefix {@code GLOBAL:},
     * {@code sessionId == null}). Used by the refresh path to load last-known snapshots for
     * non-destructive fallback and targeted stale-row cleanup without a full collection scan or a
     * destructive {@code deleteAll()}.
     */
    List<OnChainBalance> findBySessionIdIsNull();

    void deleteAllBySessionId(String sessionId);
}
