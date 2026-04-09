package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads on-chain canonical rows that are already normalized but still miss protocolName.
 */
@Service
@RequiredArgsConstructor
public class ProtocolNameEnrichmentQueryService {

    private final MongoOperations mongoOperations;
    private final ProtocolNameCanonicalizer protocolNameCanonicalizer;

    public List<NormalizedTransaction> loadNextBatch(int batchSize) {
        return loadBatch(null, batchSize, false);
    }

    public List<NormalizedTransaction> loadRepairBatchAfter(@Nullable String afterId, int batchSize) {
        return loadBatch(afterId, batchSize, true);
    }

    private List<NormalizedTransaction> loadBatch(@Nullable String afterId, int batchSize, boolean repairSweep) {
        int boundedBatchSize = Math.max(1, batchSize);

        Criteria protocolTarget = new Criteria().orOperator(
                Criteria.where("protocolName").exists(false),
                Criteria.where("protocolName").is(null),
                Criteria.where("protocolName").is(""),
                Criteria.where("protocolName").in(protocolNameCanonicalizer.legacyExactNames())
        );

        Criteria baseCriteria = new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").ne(NormalizedTransactionStatus.PENDING_CLARIFICATION),
                protocolTarget
        );
        if (repairSweep && afterId != null && !afterId.isBlank()) {
            baseCriteria = new Criteria().andOperator(
                    baseCriteria,
                    Criteria.where("_id").gt(afterId)
            );
        }

        Query query = new Query(baseCriteria);
        query.with(repairSweep
                ? Sort.by(Sort.Order.asc("_id"))
                : Sort.by(
                        Sort.Order.asc("blockTimestamp"),
                        Sort.Order.asc("transactionIndex"),
                        Sort.Order.asc("_id")
                ));
        query.limit(boundedBatchSize);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }
}
