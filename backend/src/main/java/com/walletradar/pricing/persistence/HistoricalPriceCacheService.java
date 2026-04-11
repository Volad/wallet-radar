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
