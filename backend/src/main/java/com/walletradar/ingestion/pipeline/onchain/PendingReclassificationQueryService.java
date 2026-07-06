package com.walletradar.ingestion.pipeline.onchain;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads rows that completed clarification and need the normal classifier pass.
 */
@Service
@RequiredArgsConstructor
public class PendingReclassificationQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadNextBatch(int batchSize) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").is(NormalizedTransactionStatus.PENDING_RECLASSIFICATION)
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }
}
