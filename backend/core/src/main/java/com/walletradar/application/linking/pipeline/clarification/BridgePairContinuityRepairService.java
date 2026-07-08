package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cycle/14: re-evaluates legacy sealed bridge pairs that were linked in earlier cycles but left
 * {@code continuityCandidate=false} on the OUT leg because linker services only process unpaired rows.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BridgePairContinuityRepairService {

    private static final String BRIDGE_CORRELATION_PREFIX = "^bridge:";
    private static final String COUNTERPARTY_TYPE_MISSING_REASON = "STAT_COUNTERPARTY_TYPE_MISSING";
    private static final String FLOW_COUNTERPARTY_MISSING_REASON = "FLOW_COUNTERPARTY_MISSING";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcilePairedInboundCounterparty(int batchSize) {
        List<NormalizedTransaction> batch = loadInboundsMissingCounterparty(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;
        for (NormalizedTransaction inbound : batch) {
            NormalizedTransaction outbound = findPairedOutbound(inbound);
            if (outbound == null) {
                continue;
            }
            if (repairInboundCounterparty(outbound, inbound, now, dirty)) {
                repaired++;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicateById(dirty));
            log.info("BRIDGE_PAIR_COUNTERPARTY_REPAIR batch={} repaired={} saved={}", batch.size(), repaired, dirty.size());
        }
        return repaired;
    }

    /**
     * Cycle/N: repairs BRIDGE_IN rows that were linked (correlationId + matchedCounterparty set)
     * but left with continuityCandidate=false — causing ACQUIRE instead of CARRY_IN in replay.
     * Uses the inbound-only repair pattern to avoid demoting the already-correct BRIDGE_OUT status.
     */
    public int reconcileLegacySealedInbounds(int batchSize) {
        List<NormalizedTransaction> batch = loadLegacySealedInbounds(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;
        for (NormalizedTransaction inbound : batch) {
            if (repairInboundFromSealedPair(inbound, now, dirty)) {
                repaired++;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicateById(dirty));
            log.info("BRIDGE_IN_SEALED_REPAIR batch={} repaired={} saved={}", batch.size(), repaired, dirty.size());
        }
        return repaired;
    }

    public int reconcileLegacySealedPairs(int batchSize) {
        List<NormalizedTransaction> batch = loadLegacySealedOutbounds(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;
        for (NormalizedTransaction outbound : batch) {
            if (repairPair(outbound, now, dirty)) {
                repaired++;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicateById(dirty));
            log.info("BRIDGE_PAIR_CONTINUITY_REPAIR batch={} repaired={} saved={}", batch.size(), repaired, dirty.size());
        }
        return repaired;
    }

    private boolean repairInboundCounterparty(
            NormalizedTransaction outbound,
            NormalizedTransaction inbound,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        boolean changed = BridgePairLinkSupport.applyLinkedBridgeCounterparty(outbound, inbound, now);
        if (BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound)
                && !Boolean.TRUE.equals(inbound.getContinuityCandidate())) {
            inbound.setContinuityCandidate(true);
            changed = true;
        }
        if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(inbound, now)) {
            changed = true;
        }
        if (inbound.getMissingDataReasons() != null) {
            List<String> reasons = new ArrayList<>(inbound.getMissingDataReasons());
            if (reasons.remove(COUNTERPARTY_TYPE_MISSING_REASON)
                    | reasons.remove(FLOW_COUNTERPARTY_MISSING_REASON)) {
                inbound.setMissingDataReasons(reasons);
                changed = true;
            }
        }
        NormalizedTransactionStatus targetStatus = PriceableFlowPolicy.statusAfterContinuityRetag(inbound);
        if (inbound.getStatus() != targetStatus) {
            inbound.setStatus(targetStatus);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        inbound.setUpdatedAt(now);
        dirty.add(inbound);
        return true;
    }

    private boolean repairPair(NormalizedTransaction outbound, Instant now, List<NormalizedTransaction> dirty) {
        if (outbound == null || Boolean.TRUE.equals(outbound.getContinuityCandidate())) {
            return false;
        }
        if (outbound.getType() != NormalizedTransactionType.BRIDGE_OUT || blank(outbound.getCorrelationId())) {
            return false;
        }
        NormalizedTransaction inbound = findPairedInbound(outbound);
        if (inbound == null || !BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound)) {
            return false;
        }
        boolean changed = applyContinuityRepair(outbound, inbound, now);
        if (changed) {
            dirty.add(outbound);
            dirty.add(inbound);
        }
        return changed;
    }

    static boolean applyContinuityRepair(
            NormalizedTransaction outbound,
            NormalizedTransaction inbound,
            Instant now
    ) {
        boolean changed = false;
        if (!Boolean.TRUE.equals(outbound.getContinuityCandidate())) {
            outbound.setContinuityCandidate(true);
            changed = true;
        }
        if (!Boolean.TRUE.equals(inbound.getContinuityCandidate())) {
            inbound.setContinuityCandidate(true);
            changed = true;
        }
        if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(outbound, now)) {
            changed = true;
        }
        if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(inbound, now)) {
            changed = true;
        }
        if (BridgePairLinkSupport.applyLinkedBridgeCounterparty(outbound, inbound, now)) {
            changed = true;
        }
        if (changed) {
            NormalizedTransactionStatus targetStatus = PriceableFlowPolicy.statusAfterContinuityRetag(outbound);
            if (outbound.getStatus() != targetStatus) {
                outbound.setStatus(targetStatus);
            }
            NormalizedTransactionStatus inboundStatus = PriceableFlowPolicy.statusAfterContinuityRetag(inbound);
            if (inbound.getStatus() != inboundStatus) {
                inbound.setStatus(inboundStatus);
            }
        }
        if (changed && now != null) {
            outbound.setUpdatedAt(now);
            inbound.setUpdatedAt(now);
        }
        return changed;
    }

    private List<NormalizedTransaction> loadInboundsMissingCounterparty(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                Criteria.where("correlationId").regex(BRIDGE_CORRELATION_PREFIX),
                Criteria.where("matchedCounterparty").exists(true).ne(null).ne(""),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.NEEDS_REVIEW,
                        NormalizedTransactionStatus.PENDING_STAT,
                        NormalizedTransactionStatus.PENDING_PRICE
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize) * 4);
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::needsInboundCounterpartyRepair)
                .limit(Math.max(1, batchSize))
                .toList();
    }

    private boolean needsInboundCounterpartyRepair(NormalizedTransaction inbound) {
        if (inbound == null) {
            return false;
        }
        if (FlowCounterpartySupport.flowsMissingCounterparty(inbound)) {
            return true;
        }
        if (inbound.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            return false;
        }
        if (!Boolean.TRUE.equals(inbound.getContinuityCandidate())) {
            return false;
        }
        List<String> reasons = inbound.getMissingDataReasons();
        return reasons != null && (reasons.contains(COUNTERPARTY_TYPE_MISSING_REASON)
                || reasons.contains(FLOW_COUNTERPARTY_MISSING_REASON));
    }

    private NormalizedTransaction findPairedOutbound(NormalizedTransaction inbound) {
        if (inbound == null || blank(inbound.getCorrelationId())) {
            return null;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("correlationId").is(inbound.getCorrelationId()),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("_id").ne(inbound.getId())
        ));
        List<NormalizedTransaction> matches = mongoOperations.find(query, NormalizedTransaction.class);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (!blank(inbound.getMatchedCounterparty())) {
            return matches.stream()
                    .filter(candidate -> Objects.equals(candidate.getTxHash(), inbound.getMatchedCounterparty()))
                    .findFirst()
                    .orElse(matches.getFirst());
        }
        return matches.getFirst();
    }

    private NormalizedTransaction findPairedInbound(NormalizedTransaction outbound) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("correlationId").is(outbound.getCorrelationId()),
                Criteria.where("_id").ne(outbound.getId()),
                new Criteria().orOperator(
                        Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                        Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN)
                )
        ));
        List<NormalizedTransaction> matches = mongoOperations.find(query, NormalizedTransaction.class);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (!blank(outbound.getMatchedCounterparty())) {
            return matches.stream()
                    .filter(candidate -> Objects.equals(candidate.getTxHash(), outbound.getMatchedCounterparty()))
                    .findFirst()
                    .orElse(matches.getFirst());
        }
        return matches.getFirst();
    }

    private List<NormalizedTransaction> loadLegacySealedInbounds(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                Criteria.where("correlationId").regex(BRIDGE_CORRELATION_PREFIX),
                Criteria.where("continuityCandidate").ne(true),
                Criteria.where("matchedCounterparty").exists(true).ne(null).ne("")
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
     * Inbound-only repair: does NOT call applyContinuityRepair (which would demote the
     * already-correct BRIDGE_OUT from CONFIRMED to PENDING_STAT). Delegates to the existing
     * repairInboundCounterparty pattern that only modifies and saves the inbound.
     */
    private boolean repairInboundFromSealedPair(
            NormalizedTransaction inbound,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        if (inbound == null || Boolean.TRUE.equals(inbound.getContinuityCandidate())) {
            return false;
        }
        NormalizedTransaction outbound = findPairedOutbound(inbound);
        if (outbound == null) {
            return false;
        }
        if (!BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound)) {
            return false;
        }
        return repairInboundCounterparty(outbound, inbound, now, dirty);
    }

    private List<NormalizedTransaction> loadLegacySealedOutbounds(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("continuityCandidate").ne(true),
                Criteria.where("correlationId").regex(BRIDGE_CORRELATION_PREFIX)
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private List<NormalizedTransaction> deduplicateById(List<NormalizedTransaction> candidates) {
        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || blank(candidate.getId())) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.getId(), candidate);
        }
        return List.copyOf(deduplicated.values());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
