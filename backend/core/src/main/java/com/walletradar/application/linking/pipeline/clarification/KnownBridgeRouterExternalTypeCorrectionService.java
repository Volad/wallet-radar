package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.KnownBridgeRouterRegistry;
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
 * Cycle/18 R9c: promote residual {@code EXTERNAL_TRANSFER_IN/OUT} rows whose principal flow
 * counterparty is a known bridge router but the heuristic fallback left {@code cp=MULTI}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnownBridgeRouterExternalTypeCorrectionService {

    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reclassifyKnownRouterExternals(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("counterpartyAddress").is("MULTI")
        ));
        query.limit(Math.max(1, batchSize));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction candidate : candidates) {
            if (reclassifyIfKnownRouterExternal(candidate, now)) {
                dirty.add(candidate);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("KNOWN_BRIDGE_ROUTER_EXT_TYPE_CORRECTION candidates={} reclassified={}",
                    candidates.size(), dirty.size());
        }
        return dirty.size();
    }

    private boolean reclassifyIfKnownRouterExternal(NormalizedTransaction transaction, Instant now) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        if (!KnownBridgeRouterRegistry.touchesKnownBridgeRouter(principalFlowCounterparties(transaction))) {
            return false;
        }
        int inboundSign = 0;
        int outboundSign = 0;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getQuantityDelta().signum() > 0) {
                inboundSign++;
            } else {
                outboundSign++;
            }
        }
        NormalizedTransactionType targetType;
        if (inboundSign > 0 && outboundSign == 0) {
            targetType = NormalizedTransactionType.BRIDGE_IN;
        } else if (outboundSign > 0 && inboundSign == 0) {
            targetType = NormalizedTransactionType.BRIDGE_OUT;
        } else {
            return false;
        }
        boolean changed = false;
        if (transaction.getType() != targetType) {
            transaction.setType(targetType);
            changed = true;
        }
        if (retagPrincipalFlowsForBridgeContinuity(transaction)) {
            changed = true;
        }
        if (ensureBridgeMissingReason(transaction)) {
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private static List<String> principalFlowCounterparties(NormalizedTransaction transaction) {
        List<String> counterparties = new ArrayList<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getCounterpartyAddress() != null && !flow.getCounterpartyAddress().isBlank()) {
                counterparties.add(flow.getCounterpartyAddress());
            }
        }
        return counterparties;
    }

    private static boolean retagPrincipalFlowsForBridgeContinuity(NormalizedTransaction transaction) {
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                flow.setRole(NormalizedLegRole.TRANSFER);
                changed = true;
            }
            if (flow.getUnitPriceUsd() != null) {
                flow.setUnitPriceUsd(null);
                changed = true;
            }
            if (flow.getValueUsd() != null) {
                flow.setValueUsd(null);
                changed = true;
            }
            if (flow.getPriceSource() != null) {
                flow.setPriceSource(null);
                changed = true;
            }
            if (flow.getAvcoAtTimeOfSale() != null) {
                flow.setAvcoAtTimeOfSale(null);
                changed = true;
            }
            if (flow.getRealisedPnlUsd() != null) {
                flow.setRealisedPnlUsd(null);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean ensureBridgeMissingReason(NormalizedTransaction transaction) {
        List<String> reasons = transaction.getMissingDataReasons();
        if (reasons == null) {
            transaction.setMissingDataReasons(new ArrayList<>(List.of(BRIDGE_MISSING_REASON)));
            return true;
        }
        if (reasons.contains(BRIDGE_MISSING_REASON)) {
            return false;
        }
        reasons.add(BRIDGE_MISSING_REASON);
        return true;
    }
}
