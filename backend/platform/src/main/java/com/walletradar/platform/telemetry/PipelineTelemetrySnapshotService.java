package com.walletradar.platform.telemetry;

import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.MissingDataReasons;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Produces stage-agnostic operational counters for normalized and replay state.
 */
@Service
@RequiredArgsConstructor
public class PipelineTelemetrySnapshotService {

    private static final String ORPHAN_UTA_REASON = "UTA_TRADE_PAIR_NOT_FOUND";
    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );
    private static final Criteria EXCLUDED_ACCOUNTING_CRITERIA = Criteria.where("excludedFromAccounting").is(Boolean.TRUE);

    private final MongoOperations mongoOperations;

    public PipelineTelemetrySnapshot snapshot() {
        return new PipelineTelemetrySnapshot(
                countNormalizedBySource(NormalizedTransactionSource.ON_CHAIN),
                countNormalizedBySource(NormalizedTransactionSource.BYBIT),
                countNormalizedByStatus(NormalizedTransactionStatus.PENDING_STAT),
                countUnmatchedBybitBridge(),
                countBybitOrphans(),
                countUnresolvedPrice(),
                countBlockingNeedsReview(),
                countExcludedNeedsReview()
        );
    }

    private long countNormalizedBySource(NormalizedTransactionSource source) {
        Query query = new Query(Criteria.where("source").is(source));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countNormalizedByStatus(NormalizedTransactionStatus status) {
        Query query = new Query(Criteria.where("status").is(status));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countUnmatchedBybitBridge() {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("sourceFileType").is("withdraw_deposit"),
                Criteria.where("onChainCorrelation.status").is("UNMATCHED")
        ));
        return mongoOperations.count(query, ExternalLedgerRaw.class)
                + mongoOperations.count(query, BybitExtractedEvent.class);
    }

    private long countBybitOrphans() {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("missingDataReasons").in(ORPHAN_UTA_REASON)
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countUnresolvedPrice() {
        Query query = new Query(Criteria.where("missingDataReasons").in(MissingDataReasons.PRICE_UNRESOLVABLE));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countBlockingNeedsReview() {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countExcludedNeedsReview() {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                EXCLUDED_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }
}
