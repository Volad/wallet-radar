package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.PriceSource;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * F-5(a): cross-network market-at-timestamp lookup — finds a same-minute quote for a fungible
     * canonical asset stored under any of the candidate symbols, regardless of the network/contract
     * it was originally priced on. Backed by the {@code historical_price_symbol_bucket_source_idx}
     * index.
     */
    Optional<HistoricalPriceDocument> findFirstBySymbolInAndBucketStartAndSource(
            Collection<String> symbols,
            Instant bucketStart,
            PriceSource source
    );

    /**
     * RC-D (ADR-043, F-7) — earliest cached bucket at or after {@code bucketStart} for a given
     * asset/source. Used to bound a pre-coverage (out-of-range) price request to the nearest valid
     * bucket instead of a far, wrong value. Backed by the
     * {@code historical_price_asset_bucket_source_idx} index.
     */
    Optional<HistoricalPriceDocument> findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
            String assetKey,
            PriceSource source,
            Instant bucketStart
    );

    /**
     * RC-D (ADR-043, F-7) — latest cached bucket at or before {@code bucketStart} for a given
     * asset/source (the other half of the bounded nearest-valid-bucket lookup).
     */
    Optional<HistoricalPriceDocument> findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
            String assetKey,
            PriceSource source,
            Instant bucketStart
    );
}
