package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Cycle/9 S5 + Cycle/11 S2: Fallback pricing for {@code EXTERNAL_TRANSFER_IN} legs whose
 * continuity carry peer never materialized in our session.
 *
 * <p>On-chain path: orphan when no {@code EXTERNAL_TRANSFER_OUT} exists for
 * {@code (txHash, networkId, matchedCounterparty)}.</p>
 *
 * <p>Bybit path: orphan when no {@code INTERNAL_TRANSFER} shares the same {@code correlationId}
 * (sub-account corridor / collapsed-stream pairing). Bybit rows do not pair on
 * {@code (txHash, networkId, walletAddress)} the way on-chain legs do.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UnmatchedExternalTransferInPricingFallbackService {

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOrphanInbounds() {
        int onChain = reconcileOnChainOrphanInbounds();
        int bybit = reconcileBybitOrphanInbounds();
        return onChain + bybit;
    }

    int reconcileOnChainOrphanInbounds() {
        Query inboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("continuityCandidate").is(true),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("matchedCounterparty").exists(true).ne("")
        ));
        List<NormalizedTransaction> inbounds = mongoOperations.find(inboundQuery, NormalizedTransaction.class);
        if (inbounds.isEmpty()) {
            return 0;
        }
        Set<String> peerKeys = new HashSet<>();
        for (NormalizedTransaction inbound : inbounds) {
            String key = onChainPeerKey(inbound);
            if (key != null) {
                peerKeys.add(key);
            }
        }
        if (peerKeys.isEmpty()) {
            return 0;
        }

        Query outboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("txHash").exists(true).ne("")
        ));
        outboundQuery.fields()
                .include("txHash")
                .include("networkId")
                .include("walletAddress");
        List<NormalizedTransaction> outbounds = mongoOperations.find(outboundQuery, NormalizedTransaction.class);
        Set<String> matchedKeys = new HashSet<>();
        for (NormalizedTransaction outbound : outbounds) {
            String key = onChainOutboundKey(outbound);
            if (key != null && peerKeys.contains(key)) {
                matchedKeys.add(key);
            }
        }

        return demoteOrphans(inbounds, inbound -> {
            String key = onChainPeerKey(inbound);
            return key != null && matchedKeys.contains(key);
        }, "ON_CHAIN");
    }

    int reconcileBybitOrphanInbounds() {
        Query inboundQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("continuityCandidate").is(true)
        ));
        List<NormalizedTransaction> inbounds = mongoOperations.find(inboundQuery, NormalizedTransaction.class);
        if (inbounds.isEmpty()) {
            return 0;
        }
        Set<String> correlationIds = new HashSet<>();
        for (NormalizedTransaction inbound : inbounds) {
            if (!isBlank(inbound.getCorrelationId())) {
                correlationIds.add(inbound.getCorrelationId().trim());
            }
        }
        Set<String> pairedCorrelations = new HashSet<>();
        if (!correlationIds.isEmpty()) {
            Query internalQuery = Query.query(new Criteria().andOperator(
                    Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                    Criteria.where("correlationId").in(correlationIds)
            ));
            internalQuery.fields().include("correlationId");
            List<NormalizedTransaction> internals = mongoOperations.find(internalQuery, NormalizedTransaction.class);
            for (NormalizedTransaction internal : internals) {
                if (!isBlank(internal.getCorrelationId())) {
                    pairedCorrelations.add(internal.getCorrelationId().trim());
                }
            }
        }

        return demoteOrphans(inbounds, inbound -> {
            if (isBlank(inbound.getCorrelationId())) {
                return false;
            }
            return pairedCorrelations.contains(inbound.getCorrelationId().trim());
        }, "BYBIT");
    }

    private int demoteOrphans(
            List<NormalizedTransaction> inbounds,
            Predicate<NormalizedTransaction> hasPeer,
            String channel
    ) {
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int skippedWithPeer = 0;
        for (NormalizedTransaction inbound : inbounds) {
            if (hasPeer.test(inbound)) {
                skippedWithPeer++;
                continue;
            }
            if (clearContinuityAndRepriceTrigger(inbound, now)) {
                dirty.add(inbound);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
        }
        if (!inbounds.isEmpty()) {
            log.info(
                    "UNMATCHED_EXTERNAL_TRANSFER_IN_PRICING_FALLBACK channel={} candidates={} matched_peers={} reset_to_pending_price={}",
                    channel,
                    inbounds.size(),
                    skippedWithPeer,
                    dirty.size()
            );
        }
        return dirty.size();
    }

    private static String onChainPeerKey(NormalizedTransaction inbound) {
        if (inbound == null
                || isBlank(inbound.getTxHash())
                || inbound.getNetworkId() == null
                || isBlank(inbound.getMatchedCounterparty())) {
            return null;
        }
        return inbound.getTxHash().trim().toLowerCase()
                + "|" + inbound.getNetworkId().name()
                + "|" + inbound.getMatchedCounterparty().trim().toLowerCase();
    }

    private static String onChainOutboundKey(NormalizedTransaction outbound) {
        if (outbound == null
                || isBlank(outbound.getTxHash())
                || outbound.getNetworkId() == null
                || isBlank(outbound.getWalletAddress())) {
            return null;
        }
        return outbound.getTxHash().trim().toLowerCase()
                + "|" + outbound.getNetworkId().name()
                + "|" + outbound.getWalletAddress().trim().toLowerCase();
    }

    private boolean clearContinuityAndRepriceTrigger(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            tx.setContinuityCandidate(false);
            changed = true;
        }
        if (tx.getFlows() != null) {
            for (NormalizedTransaction.Flow flow : tx.getFlows()) {
                if (flow == null) {
                    continue;
                }
                if (flow.getRole() == NormalizedLegRole.TRANSFER
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() > 0) {
                    flow.setRole(NormalizedLegRole.BUY);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return false;
        }
        if (tx.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
            tx.setConfirmedAt(null);
        }
        tx.setUpdatedAt(now);
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
