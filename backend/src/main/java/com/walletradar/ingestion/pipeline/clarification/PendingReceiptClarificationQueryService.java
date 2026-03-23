package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
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
 * Loads due full-receipt clarification candidates from the residual review tail.
 */
@Service
@RequiredArgsConstructor
public class PendingReceiptClarificationQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadNextBatch(int batchSize, int maxAttempts, long retryDelaySeconds) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        Instant retryCutoff = Instant.now().minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("fullReceiptClarificationAttempts").exists(false),
                Criteria.where("fullReceiptClarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );
        Criteria reasonsCriteria = Criteria.where("missingDataReasons").in(
                "ROUTER_METHOD_OVERLOAD_UNSUPPORTED",
                "CLASSIFICATION_FAILED"
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                attemptsCriteria,
                dueCriteria,
                reasonsCriteria
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
