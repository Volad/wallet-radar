package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
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
import java.util.Map;
import java.util.Set;

/**
 * Tags outbound transfers that were sent through a known scam/phishing disperse-clone contract.
 *
 * <p>The real Disperse.app on Arbitrum is {@code 0xD152f549…1452150} with selector
 * {@code 0xe63d38ed}. Known phishing clones use different contracts and selectors to
 * trick wallets into displaying a familiar-looking confirmation modal.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScamDisperseClonePhishingTagger {

    private static final String REASON = "SUSPECTED_PHISHING_OUT";
    private static final String PROTOCOL = "DisperseClone:Scam";
    private static final String CP_TYPE_SCAM = "SCAM";

    private static final Set<String> SCAM_CONTRACTS = Set.of(
            "0xde7169fe7285aeb4a4a8aa6b4a33f425c1e843f9"
    );
    private static final Set<String> SCAM_SELECTORS = Set.of(
            "0x0cf79e0a"
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int tagPhishingOutbounds(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (tagIfScamDisperse(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("SCAM_DISPERSE_TAGGER tagged={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT),
                Criteria.where("missingDataReasons").nin(REASON)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean tagIfScamDisperse(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getTxHash() == null || tx.getNetworkId() == null) {
            return false;
        }
        RawTransaction raw = findRaw(tx);
        if (raw == null || raw.getRawData() == null) {
            return false;
        }
        if (!matchesScamSignature(raw)) {
            return false;
        }
        return applyTag(tx, now);
    }

    boolean matchesScamSignature(RawTransaction raw) {
        Document rawData = raw.getRawData();
        if (rawData == null) {
            return false;
        }
        String methodId = rawData.getString("methodId");
        if (methodId == null || !SCAM_SELECTORS.contains(methodId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Document explorer = rawData.get("explorer", Document.class);
        if (explorer == null) {
            return false;
        }
        List<Document> tokenTransfers = explorer.getList("tokenTransfers", Document.class);
        if (tokenTransfers == null || tokenTransfers.isEmpty()) {
            return false;
        }
        boolean hasScamContract = false;
        for (Document tt : tokenTransfers) {
            String contract = tt.getString("contractAddress");
            if (contract != null && SCAM_CONTRACTS.contains(contract.toLowerCase(Locale.ROOT))) {
                hasScamContract = true;
                break;
            }
        }
        return hasScamContract;
    }

    private boolean applyTag(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (!PROTOCOL.equals(tx.getProtocolName())) {
            tx.setProtocolName(PROTOCOL);
            changed = true;
        }
        if (!CP_TYPE_SCAM.equals(tx.getCounterpartyType())) {
            tx.setCounterpartyType(CP_TYPE_SCAM);
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
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow != null && flow.getRole() != NormalizedLegRole.FEE) {
                if (!CP_TYPE_SCAM.equals(flow.getCounterpartyType())) {
                    flow.setCounterpartyType(CP_TYPE_SCAM);
                    changed = true;
                }
            }
        }
        if (changed) {
            tx.setUpdatedAt(now);
        }
        return changed;
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
}
