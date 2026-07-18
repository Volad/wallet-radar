package com.walletradar.application.pricing.latest;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface TrackedPriceAssetRepository extends MongoRepository<TrackedPriceAssetDocument, String> {

    void deleteByLastSeenAtBefore(Instant threshold);
}
