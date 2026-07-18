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
 * RC-3: Tags transactions that have any flow (positive or negative) involving Aave V3
 * protocol-internal variable debt tracking token contracts on AVALANCHE.
 *
 * <p>These tokens (variableDebtAvaUSDT, variableDebtAvaEURC) are pure liability instruments
 * emitted by Aave V3 to record a user's outstanding variable-rate borrow position.
 * They carry no real economic value for the user and must never enter the AVCO ledger.
 * Any transaction touching these contracts is excluded from accounting.</p>
 *
 * <p>Registered contracts (AVALANCHE only):
 * <ul>
 *   <li>{@code 0xfb00ac187a8eb5afae4eace434f493eb62672df7} — variableDebtAvaUSDT</li>
 *   <li>{@code 0x5d557b07776d12967914379c71a1310e917c7555} — variableDebtAvaEURC</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AaveVariableDebtTokenTagger {

    public static final String REASON = ClassificationReasonCode.AAVE_VARIABLE_DEBT_TOKEN.code();

    /**
     * Aave V3 internal variable-debt tracking token contracts on AVALANCHE.
     * All entries are pre-lowercased for O(1) lookup.
     */
    static final Set<String> AAVE_VARIABLE_DEBT_CONTRACTS_AVALANCHE = Set.of(
            "0xfb00ac187a8eb5afae4eace434f493eb62672df7",  // variableDebtAvaUSDT
            "0x5d557b07776d12967914379c71a1310e917c7555"   // variableDebtAvaEURC
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int tagDebtTokenFlows(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (tagIfAaveVariableDebtToken(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("AAVE_VARIABLE_DEBT_TOKEN_TAGGER tagged={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("networkId").is(NetworkId.AVALANCHE),
                Criteria.where("flows.assetContract").in(AAVE_VARIABLE_DEBT_CONTRACTS_AVALANCHE),
                Criteria.where("missingDataReasons").nin(REASON)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean tagIfAaveVariableDebtToken(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getNetworkId() != NetworkId.AVALANCHE) {
            return false;
        }
        if (!hasVariableDebtTokenFlow(tx)) {
            return false;
        }
        return applyTag(tx, now);
    }

    /**
     * Returns {@code true} when any flow (positive OR negative) touches an Aave V3 internal
     * variable-debt token contract. Negative flows arise in transactions that burn debt tokens
     * as part of repayment — these must also be excluded so that the accounting engine does not
     * try to drain inventory that was never credited.
     */
    static boolean hasVariableDebtTokenFlow(NormalizedTransaction tx) {
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
            if (AAVE_VARIABLE_DEBT_CONTRACTS_AVALANCHE.contains(
                    flow.getAssetContract().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
