package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BLOCKER-9 / ADR-057: Tags transactions that receive positive inflows from Euler Finance v2 EVK
 * internal variable-debt tracking token contracts.
 *
 * <p>These tokens are pure protocol-internal mechanics (debt-position bookkeeping tokens emitted
 * when a user opens or rebalances a leveraged position). They carry no real economic value for the
 * user and must never enter the AVCO ledger. Any transaction with a positive inbound flow from
 * one of these contracts is excluded from accounting.</p>
 *
 * <p>Registered contracts (AVALANCHE only):
 * <ul>
 *   <li>{@code 0x2eb15b5e4e5749bdd46a8cca48c500f69bd0df5d} — Euler EVK variable-debt token</li>
 *   <li>{@code 0x1d45674ec811f8a33c97616790bc5a81d4c9afac} — Euler EVK variable-debt token</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EulerEvkDebtTokenTagger {

    public static final String REASON = ClassificationReasonCode.EULER_EVK_INTERNAL_DEBT_TOKEN.code();

    /**
     * Euler Finance v2 EVK internal variable-debt tracking token contracts on AVALANCHE.
     * All entries are pre-lowercased for O(1) lookup.
     */
    static final Set<String> EULER_EVK_DEBT_CONTRACTS_AVALANCHE = Set.of(
            "0x2eb15b5e4e5749bdd46a8cca48c500f69bd0df5d",
            "0x1d45674ec811f8a33c97616790bc5a81d4c9afac"
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int tagDebtTokenInflows(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (tagIfEulerEvkDebtToken(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("EULER_EVK_DEBT_TOKEN_TAGGER tagged={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("networkId").is(NetworkId.AVALANCHE),
                Criteria.where("flows.assetContract").in(EULER_EVK_DEBT_CONTRACTS_AVALANCHE),
                Criteria.where("missingDataReasons").nin(REASON)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean tagIfEulerEvkDebtToken(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getNetworkId() != NetworkId.AVALANCHE) {
            return false;
        }
        if (!hasDebtTokenFlow(tx)) {
            return false;
        }
        return applyTag(tx, now);
    }

    /**
     * Returns {@code true} when any flow (positive OR negative) touches an Euler EVK internal
     * debt-token contract. Negative flows arise in LENDING_DEPOSIT transactions that burn debt
     * tokens as part of the leveraged-loop exit — these must also be excluded so that the
     * accounting engine does not try to drain inventory that was never credited (the corresponding
     * LENDING_LOOP_REBALANCE credit is already excluded).
     */
    static boolean hasDebtTokenFlow(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getAssetContract() == null) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (EULER_EVK_DEBT_CONTRACTS_AVALANCHE.contains(
                    flow.getAssetContract().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated Use {@link #hasDebtTokenFlow(NormalizedTransaction)} instead. */
    @Deprecated
    static boolean hasDebtTokenPositiveInflow(NormalizedTransaction tx) {
        return hasDebtTokenFlow(tx);
    }

    private boolean applyTag(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (!Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
            tx.setExcludedFromAccounting(true);
            tx.setAccountingExclusionReason(REASON);
            changed = true;
        }
        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            reasons = new ArrayList<>();
            tx.setMissingDataReasons(reasons);
        }
        if (!reasons.contains(REASON)) {
            reasons.add(REASON);
            changed = true;
        }
        if (changed) {
            tx.setUpdatedAt(now);
        }
        return changed;
    }
}
