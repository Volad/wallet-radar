package com.walletradar.domain.transaction.session;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for session-scoped transaction timeline.
 */
public interface SessionTransactionRepository extends MongoRepository<SessionTransaction, String> {

    void deleteBySessionIdAndSourceType(String sessionId, SessionTransactionSourceType sourceType);

    List<SessionTransaction> findBySessionId(String sessionId, Pageable pageable);
}
