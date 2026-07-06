package com.walletradar.pricing.persistence;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.pricing.domain.PriceBucketResolution;
import com.walletradar.pricing.domain.PriceQuote;
import com.walletradar.pricing.domain.PriceRequest;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic historical price cache facade.
 */
@Service
public class HistoricalPriceCacheService {

    private static final PriceBucketResolution DEFAULT_BUCKET_RESOLUTION = PriceBucketResolution.MINUTE;

    private final HistoricalPriceRepository historicalPriceRepository;
    private final MongoTemplate mongoTemplate;

    public HistoricalPriceCacheService(
            HistoricalPriceRepository historicalPriceRepository,
            MongoTemplate mongoTemplate
    ) {
        this.historicalPriceRepository = Objects.requireNonNull(historicalPriceRepository, "historicalPriceRepository");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate");
    }

    public Optional<PriceQuote> findQuote(PriceRequest request, PriceSource source) {
        Instant bucketStart = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        return historicalPriceRepository.findByAssetKeyAndBucketStartAndSource(request.assetKey(), bucketStart, source)
                .map(this::toQuote);
    }

    /**
     * F-5(a): cross-network market-at-timestamp lookup. Resolves a same-minute quote for a fungible
     * canonical asset from any network/contract it was priced on, matched by candidate symbols.
     */
    public Optional<PriceQuote> findCanonicalQuote(
            Collection<String> candidateSymbols,
            Instant occurredAt,
            PriceSource source
    ) {
        if (candidateSymbols == null || candidateSymbols.isEmpty() || occurredAt == null) {
            return Optional.empty();
        }
        Instant bucketStart = bucketStart(occurredAt, DEFAULT_BUCKET_RESOLUTION);
        return historicalPriceRepository
                .findFirstBySymbolInAndBucketStartAndSource(candidateSymbols, bucketStart, source)
                .map(this::toQuote);
    }

    /**
     * RC-D (ADR-043, F-7) — bounded nearest-valid-bucket fallback. When the exact-minute bucket
     * misses (a pre-coverage / out-of-range request such as a 2025-01 DOGE lot that predates the
     * asset's first cached bucket), resolve to the temporally closest cached bucket for the same
     * asset+source, but only within {@code maxWindow}. Returns empty when no bucket sits within the
     * window, so a genuinely unpriceable request still fails safe instead of clamping to a far,
     * wrong value.
     */
    public Optional<PriceQuote> findNearestQuoteWithinWindow(
            PriceRequest request,
            PriceSource source,
            Duration maxWindow
    ) {
        if (request == null || source == null || maxWindow == null || maxWindow.isNegative()) {
            return Optional.empty();
        }
        Instant target = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        HistoricalPriceDocument after = historicalPriceRepository
                .findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                        request.assetKey(), source, target)
                .orElse(null);
        HistoricalPriceDocument before = historicalPriceRepository
                .findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                        request.assetKey(), source, target)
                .orElse(null);
        HistoricalPriceDocument nearest = pickNearestWithinWindow(target, before, after, maxWindow);
        return nearest == null ? Optional.empty() : Optional.of(toQuote(nearest));
    }

    /**
     * RC-D (ADR-043) — pre-coverage-only nearest bucket. Unlike {@link #findNearestQuoteWithinWindow},
     * this returns a clamp ONLY when the request is genuinely pre-coverage for the asset+source: there
     * is no cached bucket at or before the event minute. It lets a bot-derived ACQUIRE lot dated before
     * the asset's first market bucket borrow the nearest forward bucket (bounded by {@code maxWindow}),
     * while a lot that already sits inside coverage keeps its own resolution untouched.
     */
    public Optional<PriceQuote> findPreCoverageNearestQuote(
            PriceRequest request,
            PriceSource source,
            Duration maxWindow
    ) {
        if (request == null || source == null || maxWindow == null || maxWindow.isNegative()) {
            return Optional.empty();
        }
        Instant target = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        HistoricalPriceDocument before = historicalPriceRepository
                .findFirstByAssetKeyAndSourceAndBucketStartLessThanEqualOrderByBucketStartDesc(
                        request.assetKey(), source, target)
                .orElse(null);
        if (before != null) {
            // A bucket exists at or before the event minute → not pre-coverage; do not clamp.
            return Optional.empty();
        }
        HistoricalPriceDocument after = historicalPriceRepository
                .findFirstByAssetKeyAndSourceAndBucketStartGreaterThanEqualOrderByBucketStartAsc(
                        request.assetKey(), source, target)
                .orElse(null);
        HistoricalPriceDocument nearest = pickNearestWithinWindow(target, null, after, maxWindow);
        return nearest == null ? Optional.empty() : Optional.of(toQuote(nearest));
    }

    private static HistoricalPriceDocument pickNearestWithinWindow(
            Instant target,
            HistoricalPriceDocument before,
            HistoricalPriceDocument after,
            Duration maxWindow
    ) {
        HistoricalPriceDocument nearest = null;
        Duration nearestDistance = null;
        for (HistoricalPriceDocument candidate : new HistoricalPriceDocument[]{before, after}) {
            if (candidate == null
                    || candidate.getBucketStart() == null
                    || candidate.getPriceUsd() == null
                    || candidate.getPriceUsd().signum() <= 0) {
                continue;
            }
            Duration distance = Duration.between(candidate.getBucketStart(), target).abs();
            if (distance.compareTo(maxWindow) > 0) {
                continue;
            }
            if (nearestDistance == null || distance.compareTo(nearestDistance) < 0) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public HistoricalPriceDocument storeQuote(PriceRequest request, PriceQuote quote) {
        return historicalPriceRepository.save(toDocument(request, quote));
    }

    public List<HistoricalPriceDocument> storeQuotes(Collection<HistoricalPriceDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, HistoricalPriceDocument> documentsById = new LinkedHashMap<>();
        for (HistoricalPriceDocument document : documents) {
            if (document == null || document.getId() == null || document.getId().isBlank()) {
                continue;
            }
            documentsById.put(document.getId(), document);
        }
        if (documentsById.isEmpty()) {
            return List.of();
        }

        BulkOperations bulkOperations = mongoTemplate.bulkOps(
                BulkOperations.BulkMode.UNORDERED,
                HistoricalPriceDocument.class
        );
        for (HistoricalPriceDocument document : documentsById.values()) {
            bulkOperations.upsert(
                    Query.query(Criteria.where("_id").is(document.getId())),
                    upsertUpdate(document)
            );
        }
        bulkOperations.execute();
        return List.copyOf(documentsById.values());
    }

    public Map<String, HistoricalPriceDocument> findDocumentsByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, HistoricalPriceDocument> documents = new LinkedHashMap<>();
        for (HistoricalPriceDocument document : historicalPriceRepository.findAllById(ids)) {
            documents.put(document.getId(), document);
        }
        return documents;
    }

    public String documentId(PriceRequest request, PriceSource source) {
        return HistoricalPriceDocument.composeId(
                request.assetKey(),
                bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION),
                source
        );
    }

    public HistoricalPriceDocument toDocument(PriceRequest request, PriceQuote quote) {
        Instant bucketStart = bucketStart(request.occurredAt(), DEFAULT_BUCKET_RESOLUTION);
        HistoricalPriceDocument document = new HistoricalPriceDocument();
        document.setId(HistoricalPriceDocument.composeId(request.assetKey(), bucketStart, quote.source()));
        document.setAssetKey(request.assetKey());
        document.setNetworkId(request.networkId());
        document.setSymbol(request.assetSymbol());
        document.setBucketStart(bucketStart);
        document.setBucketResolution(DEFAULT_BUCKET_RESOLUTION);
        document.setSource(quote.source());
        document.setPriceUsd(quote.unitPriceUsd());
        document.setQuoteSymbol(quote.quoteSymbol());
        document.setFetchedAt(quote.pricedAt());
        return document;
    }

    public PriceQuote toQuote(HistoricalPriceDocument document) {
        return new PriceQuote(
                document.getPriceUsd(),
                document.getSource(),
                document.getBucketStart(),
                document.getQuoteSymbol(),
                document.getId()
        );
    }

    private Instant bucketStart(Instant instant, PriceBucketResolution bucketResolution) {
        return switch (bucketResolution) {
            case MINUTE -> instant.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> instant.truncatedTo(ChronoUnit.HOURS);
            case DAY -> instant.truncatedTo(ChronoUnit.DAYS);
        };
    }

    private Update upsertUpdate(HistoricalPriceDocument document) {
        return new Update()
                .set("assetKey", document.getAssetKey())
                .set("networkId", document.getNetworkId())
                .set("symbol", document.getSymbol())
                .set("bucketStart", document.getBucketStart())
                .set("bucketResolution", document.getBucketResolution())
                .set("source", document.getSource())
                .set("priceUsd", document.getPriceUsd())
                .set("quoteSymbol", document.getQuoteSymbol())
                .set("fetchedAt", document.getFetchedAt());
    }
}
