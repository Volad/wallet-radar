package com.walletradar.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * Produces live pricing readiness counters used by BE-06F handoff.
 */
@Service
@RequiredArgsConstructor
public class PricingDataGateService {

    private final MongoOperations mongoOperations;
    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );
    private static final Criteria EXCLUDED_ACCOUNTING_CRITERIA = Criteria.where("excludedFromAccounting").is(Boolean.TRUE);

    public PricingDataGateSnapshot snapshot() {
        long pendingPriceCount = countByStatus(NormalizedTransactionStatus.PENDING_PRICE);
        long pendingClarificationCount = countByStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        long pendingReclassificationCount = countByStatus(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        long needsReviewCount = countBlockingNeedsReview();
        long excludedNeedsReviewCount = countExcludedNeedsReview();
        long unresolvedPriceCount = countUnresolvedPrice();
        boolean avcoReady = pendingPriceCount == 0L
                && pendingClarificationCount == 0L
                && pendingReclassificationCount == 0L
                && needsReviewCount == 0L;
        return new PricingDataGateSnapshot(
                pendingPriceCount,
                pendingClarificationCount,
                pendingReclassificationCount,
                needsReviewCount,
                unresolvedPriceCount,
                excludedNeedsReviewCount,
                avcoReady
        );
    }

    public PricingDataGateSnapshot snapshot(Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return new PricingDataGateSnapshot(0L, 0L, 0L, 0L, 0L, 0L, true);
        }
        long pendingPriceCount = countByStatus(walletAddresses, NormalizedTransactionStatus.PENDING_PRICE);
        long pendingClarificationCount = countByStatus(walletAddresses, NormalizedTransactionStatus.PENDING_CLARIFICATION);
        long pendingReclassificationCount = countByStatus(walletAddresses, NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        long needsReviewCount = countBlockingNeedsReview(walletAddresses);
        long excludedNeedsReviewCount = countExcludedNeedsReview(walletAddresses);
        long unresolvedPriceCount = countUnresolvedPrice(walletAddresses);
        boolean avcoReady = pendingPriceCount == 0L
                && pendingClarificationCount == 0L
                && pendingReclassificationCount == 0L
                && needsReviewCount == 0L;
        return new PricingDataGateSnapshot(
                pendingPriceCount,
                pendingClarificationCount,
                pendingReclassificationCount,
                needsReviewCount,
                unresolvedPriceCount,
                excludedNeedsReviewCount,
                avcoReady
        );
    }

    private long countByStatus(NormalizedTransactionStatus status) {
        Query query = new Query(Criteria.where("status").is(status));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countByStatus(Collection<String> walletAddresses, NormalizedTransactionStatus status) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is(status)
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countBlockingNeedsReview() {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countBlockingNeedsReview(Collection<String> walletAddresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
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

    private long countExcludedNeedsReview(Collection<String> walletAddresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is(NormalizedTransactionStatus.NEEDS_REVIEW),
                EXCLUDED_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countUnresolvedPrice() {
        Query query = new Query(Criteria.where("missingDataReasons").in(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countUnresolvedPrice(Collection<String> walletAddresses) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("missingDataReasons").in(PriceableFlowPolicy.PRICE_UNRESOLVABLE_REASON)
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }
}
