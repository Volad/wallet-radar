package com.walletradar.domain.session;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for stable accounting universes used by replay/read scoping.
 */
public interface AccountingUniverseRepository extends MongoRepository<AccountingUniverse, String> {
}
