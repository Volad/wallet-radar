package com.walletradar.application.normalization.pipeline.onchain;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Loads rows that completed clarification and need the normal classifier pass.
 */
@Service
@RequiredArgsConstructor
public class PendingReclassificationQueryService {

    private static final Criteria UNRESOLVED_PAIRING_CRITERIA = new Criteria().orOperator(
            Criteria.where("correlationId").exists(false),
            Criteria.where("correlationId").is(null),
            Criteria.where("correlationId").is(""),
            Criteria.where("matchedCounterparty").exists(false),
            Criteria.where("matchedCounterparty").is(null),
            Criteria.where("matchedCounterparty").is("")
    );

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

    /**
     * Loads LI.FI bridge-out rows that already carry a source anchor but still lack a materialized inbound leg.
     */
    public List<NormalizedTransaction> loadAnchoredWithoutInboundBatch(int batchSize) {
        int limit = Math.max(1, batchSize);
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("correlationId").regex("^bridge:lifi:", "i"),
                Criteria.where("matchedCounterparty").exists(true).ne(null).ne("")
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(limit * 4);
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::lacksInboundLeg)
                .limit(limit)
                .toList();
    }

    private boolean lacksInboundLeg(NormalizedTransaction outbound) {
        if (outbound == null || outbound.getCorrelationId() == null || outbound.getCorrelationId().isBlank()) {
            return false;
        }
        String correlationId = outbound.getCorrelationId().trim();
        if (!correlationId.toLowerCase(Locale.ROOT).startsWith("bridge:lifi:")) {
            return false;
        }
        Query inboundQuery = new Query(new Criteria().andOperator(
                Criteria.where("correlationId").is(correlationId),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN)
        ));
        return mongoOperations.count(inboundQuery, NormalizedTransaction.class) == 0;
    }
}
