package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.KnownProtocolCounterpartyRegistry;
import com.walletradar.ingestion.pipeline.classification.support.KnownProtocolCounterpartyRegistry.ProtocolAttribution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stamps protocol attribution (protocolName, counterpartyType) on EXTERNAL_TRANSFER_IN/OUT
 * transactions whose counterparty address is a known protocol contract.
 *
 * <p>When the registry entry indicates {@code asBridge=true}, the type is also reclassified
 * to BRIDGE_IN/OUT, flow roles are retagged to TRANSFER, and prices are stripped so the
 * downstream {@code CrossNetworkBridgePairFallbackService} can pair the leg.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProtocolAttributionClassifier {

    private static final String BRIDGE_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int classifyProtocolAttribution(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (classifyIfKnownProtocol(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("PROTOCOL_ATTRIBUTION_CLASSIFIER stamped={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                        NormalizedTransactionType.BRIDGE_IN,
                        NormalizedTransactionType.BRIDGE_OUT
                ),
                new Criteria().orOperator(
                        Criteria.where("protocolName").exists(false),
                        Criteria.where("protocolName").is(null),
                        Criteria.where("protocolName").is("")
                )
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean classifyIfKnownProtocol(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getNetworkId() == null) {
            return false;
        }
        Optional<ProtocolAttribution> match = resolveAttribution(tx);
        if (match.isEmpty()) {
            return false;
        }
        ProtocolAttribution attr = match.get();
        boolean changed = applyStamp(tx, attr);
        if (attr.asBridge()) {
            changed |= applyBridgeReclassification(tx);
        }
        if (changed) {
            tx.setUpdatedAt(now);
        }
        return changed;
    }

    private Optional<ProtocolAttribution> resolveAttribution(NormalizedTransaction tx) {
        String topCp = tx.getCounterpartyAddress();
        Optional<ProtocolAttribution> result = KnownProtocolCounterpartyRegistry.lookup(tx.getNetworkId(), topCp);
        if (result.isPresent()) {
            return result;
        }
        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                    continue;
                }
                result = KnownProtocolCounterpartyRegistry.lookup(tx.getNetworkId(), flow.getCounterpartyAddress());
                if (result.isPresent()) {
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean applyStamp(NormalizedTransaction tx, ProtocolAttribution attr) {
        boolean changed = false;
        if (!attr.name().equals(tx.getProtocolName())) {
            tx.setProtocolName(attr.name());
            changed = true;
        }
        if (!attr.counterpartyType().equals(tx.getCounterpartyType())) {
            tx.setCounterpartyType(attr.counterpartyType());
            changed = true;
        }
        return changed;
    }

    private static boolean applyBridgeReclassification(NormalizedTransaction tx) {
        boolean changed = false;

        NormalizedTransactionType targetType = (tx.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT)
                ? NormalizedTransactionType.BRIDGE_OUT
                : NormalizedTransactionType.BRIDGE_IN;

        if (tx.getType() != targetType) {
            tx.setType(targetType);
            changed = true;
        }

        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
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
            }
        }

        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            tx.setMissingDataReasons(new ArrayList<>(List.of(BRIDGE_REASON)));
            changed = true;
        } else if (!reasons.contains(BRIDGE_REASON)) {
            reasons.add(BRIDGE_REASON);
            changed = true;
        }

        return changed;
    }
}
