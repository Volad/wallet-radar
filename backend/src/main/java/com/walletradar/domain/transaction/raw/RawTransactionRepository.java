package com.walletradar.domain.transaction.raw;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for immutable on-chain raw evidence.
 */
public interface RawTransactionRepository extends MongoRepository<RawTransaction, String> {
}
