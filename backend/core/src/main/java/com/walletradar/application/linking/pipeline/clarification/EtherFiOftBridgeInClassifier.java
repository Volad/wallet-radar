package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceCatalog;
import com.walletradar.application.normalization.pipeline.classification.onchain.protocol.ProtocolResourceDefinition;
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
 *
 * <p>The weETH OFT token set and minter proxy set are loaded (Wave W7) from the authoritative
 * {@code protocols/etherfi.json} config plane ({@code contractSets.weethOftTokens} /
 * {@code contractSets.minterProxies}) rather than hardcoded here.</p>
 */
@Service
@Slf4j
public class EtherFiOftBridgeInClassifier {

    private static final String REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String PROTOCOL = "EtherFi";
    private static final String CP_TYPE_PROTOCOL = "PROTOCOL";
    private static final String WEETH_OFT_TOKENS_ROLE = "weethOftTokens";
    private static final String MINTER_PROXIES_ROLE = "minterProxies";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final Set<String> etherFiWeethTokens;
    private final Set<String> etherFiMinterProxies;

    public EtherFiOftBridgeInClassifier(
            MongoOperations mongoOperations,
            NormalizedTransactionRepository normalizedTransactionRepository,
            ProtocolResourceCatalog protocolResourceCatalog
    ) {
        this.mongoOperations = mongoOperations;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        ProtocolResourceDefinition definition = protocolResourceCatalog.find(PROTOCOL, null)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing protocols/etherfi.json protocol resource for " + PROTOCOL));
        this.etherFiWeethTokens = definition.contractSet(WEETH_OFT_TOKENS_ROLE);
        this.etherFiMinterProxies = definition.contractSet(MINTER_PROXIES_ROLE);
        if (etherFiWeethTokens.isEmpty() || etherFiMinterProxies.isEmpty()) {
            throw new IllegalStateException(
                    "protocols/etherfi.json must define non-empty contractSets."
                            + WEETH_OFT_TOKENS_ROLE + " and contractSets." + MINTER_PROXIES_ROLE);
        }
        log.info("Loaded EtherFi OFT bridge-in registry: {} weETH OFT tokens, {} minter proxies",
                etherFiWeethTokens.size(), etherFiMinterProxies.size());
    }

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
                    && etherFiWeethTokens.contains(contract.toLowerCase(Locale.ROOT))
                    && etherFiMinterProxies.contains(from.toLowerCase(Locale.ROOT))) {
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
