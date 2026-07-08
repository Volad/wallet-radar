package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.support.GmxV2HandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stamps {@code EXTERNAL_TRANSFER_IN} transactions whose counterparty is a known GMX V2
 * handler/vault with {@code protocolName="GMX V2"} and {@code counterpartyType="PROTOCOL"}.
 *
 * <p>V2 addition: when all principal-flow CPs are composite {@code UNKNOWN:} keys (no direct
 * address), falls back to checking {@code RawTransaction.rawData.explorer.internalTransactions}
 * senders against {@link GmxV2HandlerRegistry}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GmxV2RefundClassifier {

    private static final String REASON = "GMX_EXECUTION_FEE_REFUND";
    private static final String PROTOCOL = "GMX V2";
    private static final String CP_TYPE_PROTOCOL = "PROTOCOL";
    private static final String UNKNOWN_CP_PREFIX = "UNKNOWN:";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int classifyGmxRefunds(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (classifyIfGmxRefund(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("GMX_V2_REFUND_CLASSIFIER stamped={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                Criteria.where("missingDataReasons").nin(REASON),
                Criteria.where("protocolName").in(null, "")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean classifyIfGmxRefund(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }

        boolean hasGmxHandler = false;
        boolean allCompositeUnknown = true;

        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            String cp = flow.getCounterpartyAddress();
            if (cp == null || cp.isBlank()) {
                cp = tx.getCounterpartyAddress();
            }
            if (cp != null && !cp.startsWith(UNKNOWN_CP_PREFIX)) {
                allCompositeUnknown = false;
            }
            if (GmxV2HandlerRegistry.isKnownGmxV2Handler(cp)) {
                hasGmxHandler = true;
                break;
            }
        }

        if (!hasGmxHandler && allCompositeUnknown) {
            hasGmxHandler = probeRawInternalTransactions(tx);
        }

        if (!hasGmxHandler) {
            return false;
        }
        return applyStamp(tx, now);
    }

    private boolean probeRawInternalTransactions(NormalizedTransaction tx) {
        if (tx.getTxHash() == null || tx.getNetworkId() == null) {
            return false;
        }
        RawTransaction raw = findRaw(tx);
        if (raw == null || raw.getRawData() == null) {
            return false;
        }
        return matchesGmxV2InternalSender(raw);
    }

    boolean matchesGmxV2InternalSender(RawTransaction raw) {
        Document rawData = raw.getRawData();
        if (rawData == null) {
            return false;
        }
        Document explorer = rawData.get("explorer", Document.class);
        if (explorer == null) {
            return false;
        }
        List<Document> internalTxns = explorer.getList("internalTransactions", Document.class);
        if (internalTxns == null || internalTxns.isEmpty()) {
            internalTxns = explorer.getList("internalTransfers", Document.class);
        }
        if (internalTxns == null || internalTxns.isEmpty()) {
            return false;
        }
        for (Document itx : internalTxns) {
            String from = itx.getString("from");
            if (from != null && GmxV2HandlerRegistry.isKnownGmxV2Handler(from.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private RawTransaction findRaw(NormalizedTransaction tx) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("txHash").is(tx.getTxHash()),
                Criteria.where("networkId").is(tx.getNetworkId().name())
        ));
        query.limit(1);
        List<RawTransaction> results = mongoOperations.find(query, RawTransaction.class);
        return results.isEmpty() ? null : results.getFirst();
    }

    private boolean applyStamp(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (!PROTOCOL.equals(tx.getProtocolName())) {
            tx.setProtocolName(PROTOCOL);
            changed = true;
        }
        if (!CP_TYPE_PROTOCOL.equals(tx.getCounterpartyType())) {
            tx.setCounterpartyType(CP_TYPE_PROTOCOL);
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
