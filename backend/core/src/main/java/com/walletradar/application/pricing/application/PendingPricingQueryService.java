package com.walletradar.application.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Loads due pricing candidates in deterministic replay order.
 */
@Service
@RequiredArgsConstructor
public class PendingPricingQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadNextBatch(int batchSize, long retryDelaySeconds) {
        int boundedBatchSize = Math.max(1, batchSize);
        Instant retryCutoff = Instant.now().minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("pricingAttempts").exists(false),
                Criteria.where("pricingAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_PRICE),
                dueCriteria
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(boundedBatchSize);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }
}
