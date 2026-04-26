package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads due clarification candidates in deterministic canonical order.
 */
@Service
@RequiredArgsConstructor
public class PendingClarificationQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadNextBatch(int batchSize, int maxAttempts, long retryDelaySeconds) {
        return loadNextBatch(batchSize, maxAttempts, retryDelaySeconds, null, 0L);
    }

    public List<NormalizedTransaction> claimNextBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        return loadNextBatch(batchSize, maxAttempts, retryDelaySeconds, workerId, leaseSeconds);
    }

    private List<NormalizedTransaction> loadNextBatch(
            int batchSize,
            int maxAttempts,
            long retryDelaySeconds,
            String workerId,
            long leaseSeconds
    ) {
        int boundedBatchSize = Math.max(1, batchSize);
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        Instant now = Instant.now();
        Instant retryCutoff = now.minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria attemptsCriteria = new Criteria().orOperator(
                Criteria.where("clarificationAttempts").exists(false),
                Criteria.where("clarificationAttempts").lt(boundedMaxAttempts)
        );
        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("clarificationAttempts").exists(false),
                Criteria.where("clarificationAttempts").lte(0),
                Criteria.where("updatedAt").lte(retryCutoff)
        );

        Criteria leaseCriteria = new Criteria().orOperator(
                Criteria.where("clarificationLeaseUntil").exists(false),
                Criteria.where("clarificationLeaseUntil").is(null),
                Criteria.where("clarificationLeaseUntil").lte(now)
        );
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                leaseCriteria,
                attemptsCriteria,
                dueCriteria
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(boundedBatchSize);
        List<NormalizedTransaction> selected = mongoOperations.find(query, NormalizedTransaction.class);
        if (workerId == null || workerId.isBlank() || selected.isEmpty()) {
            return selected;
        }

        List<String> ids = new ArrayList<>(selected.size());
        for (NormalizedTransaction transaction : selected) {
            ids.add(transaction.getId());
        }
        Instant leaseUntil = now.plusSeconds(Math.max(1L, leaseSeconds));
        Query claimQuery = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                leaseCriteria
        ));
        Update claim = new Update()
                .set("clarificationWorkerId", workerId)
                .set("clarificationLeaseUntil", leaseUntil)
                .set("updatedAt", now);
        mongoOperations.updateMulti(claimQuery, claim, NormalizedTransaction.class);

        Query claimedQuery = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                Criteria.where("clarificationWorkerId").is(workerId),
                Criteria.where("clarificationLeaseUntil").is(leaseUntil)
        ));
        claimedQuery.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        return mongoOperations.find(claimedQuery, NormalizedTransaction.class);
    }
}
