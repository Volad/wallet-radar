package com.walletradar.domain.transaction.dzengi;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for extracted Dzengi staging rows.
 */
public interface DzengiExtractedEventRepository extends MongoRepository<DzengiExtractedEvent, String> {
}
