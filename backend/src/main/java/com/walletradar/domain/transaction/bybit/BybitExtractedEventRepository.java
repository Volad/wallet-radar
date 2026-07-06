package com.walletradar.domain.transaction.bybit;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for extracted Bybit staging rows.
 */
public interface BybitExtractedEventRepository extends MongoRepository<BybitExtractedEvent, String> {
}
