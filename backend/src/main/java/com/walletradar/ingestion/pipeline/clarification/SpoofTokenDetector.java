package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.ingestion.pipeline.classification.support.SpoofTokenQuarantineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SF-1(a): idempotent clarification sweep that quarantines spoof tokens whose principal flow asset
 * carries a confusable homoglyph ticker on a non-canonical contract.
 *
 * <p>This is the convergence net for the classification-time guard ({@code SpoofTokenClassifier}):
 * reruns and backfills always re-stamp any confusable-symbol row that was ingested before the guard
 * existed. Detection routes through {@link SpoofTokenQuarantineSupport} so the two layers agree on
 * the verdict. A coarse non-ASCII symbol regex bounds the candidate scan; the allow-listed
 * {@code ₮} (U+20AE) glyph of real {@code USD₮0} is rejected by the in-memory predicate, so real
 * stablecoin transfers are never quarantined. The pass is idempotent: rows already tagged with the
 * reason are excluded from the candidate query.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpoofTokenDetector {

    private static final String REASON = SpoofTokenQuarantineSupport.REASON;
    /** Matches any flow symbol containing a non-ASCII code point (coarse spoof prefilter). */
    private static final String NON_ASCII_SYMBOL_REGEX = "[^\\x00-\\x7f]";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int detectAndExclude(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (hasConfusableSpoofPrincipal(tx)) {
                markExcluded(tx, now);
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("SPOOF_TOKEN_QUARANTINE excluded={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                new Criteria().orOperator(
                        Criteria.where("excludedFromAccounting").exists(false),
                        Criteria.where("excludedFromAccounting").is(false)
                ),
                Criteria.where("missingDataReasons").nin(REASON),
                Criteria.where("flows.assetSymbol").regex(NON_ASCII_SYMBOL_REGEX)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean hasConfusableSpoofPrincipal(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }
        return tx.getFlows().stream()
                .filter(flow -> flow != null && flow.getRole() != NormalizedLegRole.FEE)
                .anyMatch(flow -> SpoofTokenQuarantineSupport.isConfusableSpoofAsset(
                        tx.getNetworkId(), flow.getAssetContract(), flow.getAssetSymbol()));
    }

    private void markExcluded(NormalizedTransaction tx, Instant now) {
        tx.setExcludedFromAccounting(true);
        tx.setAccountingExclusionReason(REASON);
        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            tx.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(REASON)) {
            reasons.add(REASON);
        }
        tx.setUpdatedAt(now);
    }
}
