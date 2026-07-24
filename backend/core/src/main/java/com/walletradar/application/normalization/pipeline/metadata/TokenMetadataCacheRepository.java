package com.walletradar.application.normalization.pipeline.metadata;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data repository for the durable {@code token_metadata_cache} collection (WS-7). Keyed by the
 * deterministic {@code networkId|contract} document id for idempotent write-through upserts.
 */
public interface TokenMetadataCacheRepository extends MongoRepository<TokenMetadataCacheEntry, String> {
}
