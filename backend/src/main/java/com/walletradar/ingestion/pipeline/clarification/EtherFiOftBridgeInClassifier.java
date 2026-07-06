package com.walletradar.ingestion.pipeline.clarification;

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
import java.util.Set;

/**
 * Reclassifies {@code EXTERNAL_TRANSFER_IN} to {@code BRIDGE_IN} when the token transfer
 * comes from a known EtherFi weETH OFT (LayerZero) contract and is sent by the canonical
 * EtherFi minter proxy.
 *
 * <p>Once reclassified, the existing {@code CrossNetworkBridgePairFallbackService} can pair
 * the BRIDGE_IN with a corresponding BRIDGE_OUT on another chain.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EtherFiOftBridgeInClassifier {

    private static final String REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String PROTOCOL = "EtherFi";
    private static final String CP_TYPE_PROTOCOL = "PROTOCOL";

    private static final Set<String> ETHERFI_WEETH_TOKENS = Set.of(
            "0x1bf74c010e6320bab11e2e5a532b5ac15e0b8aa6", // Linea
            "0x04c0599ae5a44757c0af6f9ec3b93da8976c150a", // Base, BNB
            "0x01f0a31698c4d065659b9bdc21b3610292a1c506", // Scroll
            "0x5a7facb970d094b6c7ff1df0ea68d99e6e73cbff", // Optimism
            "0xc1fa6e2e8667d9be0ca938a54c7e0285e9df924a", // zkSync
            "0xa3d68b74bf0528fdd07263c60d6488749044914b", // Avalanche, Sonic, HyperEVM, Plasma
            "0x7dcc39b4d1c53cb31e1abc0e358b43987fef80f7", // Berachain, Unichain, Morph
            "0xa6cb988942610f6731e664379d15ffcfbf282b44"  // Swell
    );

    private static final Set<String> ETHERFI_MINTER_PROXIES = Set.of(
            "0xeeeeee9ec4769a09a76a83c7bc42b185872860ee"
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reclassifyEtherFiOftInbounds(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (reclassifyIfEtherFiOft(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("ETHERFI_OFT_BRIDGE_IN_CLASSIFIER reclassified={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_IN),
                new Criteria().orOperator(
                        Criteria.where("protocolName").exists(false),
                        Criteria.where("protocolName").is(null),
                        Criteria.where("protocolName").is("")
                )
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean reclassifyIfEtherFiOft(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getTxHash() == null || tx.getNetworkId() == null) {
            return false;
        }
        RawTransaction raw = findRaw(tx);
        if (raw == null || raw.getRawData() == null) {
            return false;
        }
        if (!matchesEtherFiOftSignature(raw)) {
            return false;
        }
        return applyBridgeIn(tx, now);
    }

    boolean matchesEtherFiOftSignature(RawTransaction raw) {
        Document rawData = raw.getRawData();
        if (rawData == null) {
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
        for (Document tt : tokenTransfers) {
            String contract = tt.getString("contractAddress");
            String from = tt.getString("from");
            if (contract != null && from != null
                    && ETHERFI_WEETH_TOKENS.contains(contract.toLowerCase(Locale.ROOT))
                    && ETHERFI_MINTER_PROXIES.contains(from.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean applyBridgeIn(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (tx.getType() != NormalizedTransactionType.BRIDGE_IN) {
            tx.setType(NormalizedTransactionType.BRIDGE_IN);
            changed = true;
        }
        if (!PROTOCOL.equals(tx.getProtocolName())) {
            tx.setProtocolName(PROTOCOL);
            changed = true;
        }
        if (!CP_TYPE_PROTOCOL.equals(tx.getCounterpartyType())) {
            tx.setCounterpartyType(CP_TYPE_PROTOCOL);
            changed = true;
        }
        if (retagFlowsForBridgeContinuity(tx)) {
            changed = true;
        }
        if (ensureBridgeMissingReason(tx)) {
            changed = true;
        }
        if (changed) {
            tx.setUpdatedAt(now);
        }
        return changed;
    }

    private static boolean retagFlowsForBridgeContinuity(NormalizedTransaction tx) {
        boolean changed = false;
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
        return changed;
    }

    private static boolean ensureBridgeMissingReason(NormalizedTransaction tx) {
        List<String> reasons = tx.getMissingDataReasons();
        if (reasons == null) {
            tx.setMissingDataReasons(new ArrayList<>(List.of(REASON)));
            return true;
        }
        if (reasons.contains(REASON)) {
            return false;
        }
        reasons.add(REASON);
        return true;
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
