package com.walletradar.application.normalization.pipeline.onchain;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.transaction.raw.RawTransaction;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Loads due pending raw transactions in deterministic on-chain processing order.
 */
@Service
@RequiredArgsConstructor
public class PendingRawTransactionQueryService {

    private static final List<String> ALL_ON_CHAIN_NETWORKS = Arrays.stream(NetworkId.values())
            .map(Enum::name)
            .toList();

    private static final List<String> EVM_ON_CHAIN_NETWORKS = Arrays.stream(NetworkId.values())
            .filter(networkId -> networkId != NetworkId.SOLANA && networkId != NetworkId.TON)
            .map(Enum::name)
            .toList();

    private final MongoOperations mongoOperations;

    /**
     * Loads the next batch including all supported networks (EVM + Solana).
     */
    public List<RawTransaction> loadNextBatch(int batchSize) {
        return loadNextBatch(batchSize, ALL_ON_CHAIN_NETWORKS);
    }

    /**
     * Loads the next batch restricted to EVM networks only (excludes Solana).
     */
    public List<RawTransaction> loadNextEvmBatch(int batchSize) {
        return loadNextBatch(batchSize, EVM_ON_CHAIN_NETWORKS);
    }

    /**
     * Loads the next batch restricted to Solana only.
     */
    public List<RawTransaction> loadNextSolanaBatch(int batchSize) {
        return loadNextBatch(batchSize, List.of(NetworkId.SOLANA.name()));
    }

    /**
     * Loads the next batch restricted to TON only.
     */
    public List<RawTransaction> loadNextTonBatch(int batchSize) {
        return loadNextBatch(batchSize, List.of(NetworkId.TON.name()));
    }

    private List<RawTransaction> loadNextBatch(int batchSize, List<String> networks) {
        Instant now = Instant.now();
        int boundedBatchSize = Math.max(1, batchSize);

        Criteria dueRetryCriteria = new Criteria().orOperator(
                Criteria.where("nextRetryAt").is(null),
                Criteria.where("nextRetryAt").lte(now)
        );
        Criteria matchCriteria = new Criteria().andOperator(
                Criteria.where("normalizationStatus").is(NormalizationStatus.PENDING),
                Criteria.where("networkId").in(networks),
                dueRetryCriteria
        );

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(matchCriteria),
                addNumericSortField("_sortTimestamp", "$rawData.timeStamp", "long", Long.MAX_VALUE),
                addNumericSortField("_sortTransactionIndex", "$rawData.transactionIndex", "int", Integer.MAX_VALUE),
                Aggregation.sort(Sort.by(
                        Sort.Order.asc("_sortTimestamp"),
                        Sort.Order.asc("_sortTransactionIndex"),
                        Sort.Order.asc("txHash")
                )),
                Aggregation.limit(boundedBatchSize),
                removeTemporarySortFields()
        );

        return mongoOperations.aggregate(aggregation, RawTransaction.class, RawTransaction.class).getMappedResults();
    }

    private static AggregationOperation addNumericSortField(
            String fieldName,
            String inputExpression,
            String targetType,
            Number fallbackValue
    ) {
        return context -> new Document("$addFields", new Document(fieldName, new Document("$convert",
                new Document("input", inputExpression)
                        .append("to", targetType)
                        .append("onError", fallbackValue)
                        .append("onNull", fallbackValue)
        )));
    }

    private static AggregationOperation removeTemporarySortFields() {
        return context -> new Document("$project", new Document("_sortTimestamp", 0).append("_sortTransactionIndex", 0));
    }
}
