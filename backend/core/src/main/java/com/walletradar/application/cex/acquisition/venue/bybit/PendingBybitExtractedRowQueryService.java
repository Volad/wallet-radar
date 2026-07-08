package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads due Bybit extracted staging rows in deterministic source order.
 */
@Service
@RequiredArgsConstructor
public class PendingBybitExtractedRowQueryService {

    private final MongoOperations mongoOperations;

    public List<BybitExtractedEvent> loadNextBatch(int batchSize) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(BybitExtractedEventStatus.RAW),
                Criteria.where("basisRelevant").is(Boolean.TRUE),
                new Criteria().orOperator(
                        Criteria.where("outOfScope").exists(false),
                        Criteria.where("outOfScope").is(Boolean.FALSE)
                ),
                new Criteria().orOperator(
                        Criteria.where("canonicalType").exists(false),
                        Criteria.where("canonicalType").ne("UNKNOWN_CEX")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("timeUtc"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, BybitExtractedEvent.class);
    }

    public List<BybitExtractedEvent> loadNextBridgeRematchBatch(int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);
        Criteria matchCriteria = new Criteria().andOperator(
                Criteria.where("status").is(BybitExtractedEventStatus.CONFIRMED),
                Criteria.where("basisRelevant").is(Boolean.TRUE),
                Criteria.where("sourceFileType").is("withdraw_deposit"),
                Criteria.where("txHash").ne(null),
                Criteria.where("networkId").ne(null),
                new Criteria().orOperator(
                        Criteria.where("outOfScope").exists(false),
                        Criteria.where("outOfScope").is(Boolean.FALSE)
                ),
                Criteria.where("onChainCorrelation.status").is("UNMATCHED")
        );

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(matchCriteria),
                Aggregation.sort(Sort.by(
                        Sort.Order.asc("timeUtc"),
                        Sort.Order.asc("_id")
                )),
                lookupExactRawMatches(),
                lookupExactOnChainMatches(),
                Aggregation.match(new Criteria().andOperator(
                        Criteria.where("_rawMatches.0").exists(true),
                        Criteria.where("_normalizedMatches.0").exists(true)
                )),
                Aggregation.limit(boundedBatchSize),
                removeTemporaryLookupFields()
        );

        return mongoOperations.aggregate(
                aggregation,
                BybitExtractedEvent.class,
                BybitExtractedEvent.class
        ).getMappedResults();
    }

    private static AggregationOperation lookupExactRawMatches() {
        return context -> new Document("$lookup", new Document("from", "raw_transactions")
                .append("let", new Document("txHash", "$txHash").append("networkId", "$networkId"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$and", List.of(
                                new Document("$eq", List.of("$txHash", "$$txHash")),
                                new Document("$eq", List.of("$networkId", "$$networkId"))
                        )))),
                        new Document("$project", new Document("_id", 1))
                ))
                .append("as", "_rawMatches"));
    }

    private static AggregationOperation lookupExactOnChainMatches() {
        return context -> new Document("$lookup", new Document("from", "normalized_transactions")
                .append("let", new Document("txHash", "$txHash").append("networkId", "$networkId"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$and", List.of(
                                new Document("$eq", List.of("$txHash", "$$txHash")),
                                new Document("$eq", List.of("$networkId", "$$networkId")),
                                new Document("$eq", List.of("$source", "ON_CHAIN"))
                        )))),
                        new Document("$project", new Document("_id", 1))
                ))
                .append("as", "_normalizedMatches"));
    }

    private static AggregationOperation removeTemporaryLookupFields() {
        return context -> new Document("$project",
                new Document("_rawMatches", 0)
                        .append("_normalizedMatches", 0)
        );
    }
}
