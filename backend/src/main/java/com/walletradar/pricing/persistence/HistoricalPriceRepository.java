package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.PriceSource;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Historical price cache repository.
 */
public interface HistoricalPriceRepository extends MongoRepository<HistoricalPriceDocument, String> {

    Optional<HistoricalPriceDocument> findByAssetKeyAndBucketStartAndSource(
            String assetKey,
            Instant bucketStart,
            PriceSource source
    );
}
