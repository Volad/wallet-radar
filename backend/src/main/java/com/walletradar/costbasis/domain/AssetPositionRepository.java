package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for replayed wallet asset state.
 */
public interface AssetPositionRepository extends MongoRepository<AssetPosition, String> {
}
