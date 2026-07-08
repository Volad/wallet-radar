package com.walletradar.costbasis.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Loads due stat-validation candidates in deterministic replay order.
 */
@Service
@RequiredArgsConstructor
public class PendingStatQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadNextBatch(int batchSize, long retryDelaySeconds) {
        int boundedBatchSize = Math.max(1, batchSize);
        Instant retryCutoff = Instant.now().minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("statAttempts").exists(false),
                Criteria.where("statAttempts").lte(0),
                Criteria.where("updatedAt").exists(false),
                Criteria.where("updatedAt").lte(retryCutoff)
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_STAT),
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

    public List<NormalizedTransaction> loadNextBatch(int batchSize, long retryDelaySeconds, Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return List.of();
        }
        int boundedBatchSize = Math.max(1, batchSize);
        Instant retryCutoff = Instant.now().minusSeconds(Math.max(0L, retryDelaySeconds));

        Criteria dueCriteria = new Criteria().orOperator(
                Criteria.where("statAttempts").exists(false),
                Criteria.where("statAttempts").lte(0),
                Criteria.where("updatedAt").exists(false),
                Criteria.where("updatedAt").lte(retryCutoff)
        );

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_STAT),
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

    public long countPending() {
        Query query = new Query(Criteria.where("status").is(NormalizedTransactionStatus.PENDING_STAT));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    public long countPending(Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return 0L;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_STAT)
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }
}
