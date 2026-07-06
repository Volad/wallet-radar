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
 * Loads on-chain canonical rows that are already normalized but still miss row-local counterparty metadata.
 */
@Service
@RequiredArgsConstructor
public class CounterpartyEnrichmentQueryService {

    private final MongoOperations mongoOperations;

    public List<NormalizedTransaction> loadBatchAfterId(@Nullable String afterId, int batchSize) {
        int boundedBatchSize = Math.max(1, batchSize);

        Criteria counterpartyTarget = new Criteria().orOperator(
                Criteria.where("counterpartyAddress").exists(false),
                Criteria.where("counterpartyAddress").is(null),
                Criteria.where("counterpartyAddress").is(""),
                Criteria.where("counterpartyType").exists(false),
                Criteria.where("counterpartyType").is(null),
                Criteria.where("counterpartyType").is(""),
                Criteria.where("counterpartyResolutionState").exists(false),
                Criteria.where("counterpartyResolutionState").is(null),
                Criteria.where("counterpartyResolutionState").is("")
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
                        "EXTERNAL_TRANSFER_IN",
                        "EXTERNAL_TRANSFER_OUT",
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
                counterpartyTarget
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
