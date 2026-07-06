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

    public List<NormalizedTransaction> loadBatchAfterId(@Nullable String afterId, int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);

        Criteria protocolTarget = new Criteria().orOperator(
                Criteria.where("protocolName").exists(false),
                Criteria.where("protocolName").is(null),
                Criteria.where("protocolName").is(""),
                Criteria.where("protocolName").in(protocolNameCanonicalizer.legacyExactNames()),
                Criteria.where("protocolResolutionState").exists(false),
                Criteria.where("protocolResolutionState").is(null),
                Criteria.where("protocolResolutionState").is("")
        );

        Criteria baseCriteria = new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").nin(
                        NormalizedTransactionStatus.PENDING_CLARIFICATION,
                        NormalizedTransactionStatus.PENDING_RECLASSIFICATION
                ),
                Criteria.where("type").in(
                        "SWAP",
                        "WRAP",
                        "UNWRAP",
                        "BRIDGE_OUT",
                        "BRIDGE_IN",
                        "LENDING_DEPOSIT",
                        "LENDING_WITHDRAW",
                        "BORROW",
                        "REPAY",
                        "VAULT_DEPOSIT",
                        "VAULT_WITHDRAW",
                        "LP_ENTRY",
                        "LP_EXIT",
                        "LP_ENTRY_REQUEST",
                        "LP_EXIT_REQUEST",
                        "LP_ENTRY_SETTLEMENT",
                        "LP_EXIT_SETTLEMENT",
                        "LP_EXIT_PARTIAL",
                        "LP_EXIT_FINAL",
                        "LP_FEE_CLAIM",
                        "REWARD_CLAIM",
                        "STAKING_DEPOSIT",
                        "STAKING_WITHDRAW",
                        "STAKING_WITHDRAW_REQUEST",
                        "PROTOCOL_CUSTODY_DEPOSIT",
                        "PROTOCOL_CUSTODY_WITHDRAW"
                ),
                protocolTarget
        );
        if (afterId != null && !afterId.isBlank()) {
            baseCriteria = new Criteria().andOperator(
                    baseCriteria,
                    Criteria.where("_id").gt(afterId)
            );
        }

        Query query = new Query(baseCriteria);
        query.with(Sort.by(Sort.Order.asc("_id")));
        query.limit(boundedBatchSize);
        return mongoOperations.find(query, NormalizedTransaction.class);
    }
}
