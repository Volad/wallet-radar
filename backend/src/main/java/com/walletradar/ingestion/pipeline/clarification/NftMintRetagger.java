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
 * Reclassifies {@code EXTERNAL_TRANSFER_OUT} to {@code NFT_MINT} when the raw transaction
 * shows ETH spent (value > 0) with no inbound token transfers and the target contract
 * method selector matches a known NFT mint function.
 *
 * <p>v1: methodId allowlist. Future: ERC-721/1155 bytecode interface probing.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NftMintRetagger {

    private static final Set<String> KNOWN_MINT_SELECTORS = Set.of(
            "0x2e4dbe8f", // safeMint (custom)
            "0xa3f71f33", // mint (custom)
            "0x1249c58b", // mint()
            "0xa0712d68", // mint(uint256)
            "0x40c10f19", // mint(address,uint256)
            "0x6a627842", // mint(address)
            "0xd85d3d27", // mintPublic(uint256,uint256,address)
            "0x84bb1e42", // mintTo(address)
            "0xefef39a1", // mint(uint256) — ERC-1155 variant
            "0x731133e9"  // mint(address,uint256,uint256,bytes) — ERC-1155
    );

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reclassifyNftMints(int batchSize) {
        List<NormalizedTransaction> candidates = loadCandidates(batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (NormalizedTransaction tx : candidates) {
            if (reclassifyIfNftMint(tx, now)) {
                dirty.add(tx);
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("NFT_MINT_RETAGGER reclassified={}", dirty.size());
        }
        return dirty.size();
    }

    private List<NormalizedTransaction> loadCandidates(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT)
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    boolean reclassifyIfNftMint(NormalizedTransaction tx, Instant now) {
        if (tx == null || tx.getTxHash() == null || tx.getNetworkId() == null) {
            return false;
        }
        if (!hasOnlyOutboundFlows(tx)) {
            return false;
        }
        RawTransaction raw = findRaw(tx);
        if (raw == null || raw.getRawData() == null) {
            return false;
        }
        if (!matchesNftMintSignature(raw)) {
            return false;
        }
        return applyNftMint(tx, now);
    }

    private boolean hasOnlyOutboundFlows(NormalizedTransaction tx) {
        if (tx.getFlows() == null || tx.getFlows().isEmpty()) {
            return false;
        }
        boolean hasOutbound = false;
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() == 0) {
                continue;
            }
            if (flow.getQuantityDelta().signum() > 0) {
                return false;
            }
            hasOutbound = true;
        }
        return hasOutbound;
    }

    boolean matchesNftMintSignature(RawTransaction raw) {
        Document rawData = raw.getRawData();
        if (rawData == null) {
            return false;
        }
        Object valueObj = rawData.get("value");
        if (valueObj == null) {
            return false;
        }
        String valueStr = valueObj.toString().trim();
        if (valueStr.isEmpty() || "0".equals(valueStr)) {
            return false;
        }
        String methodId = rawData.getString("methodId");
        if (methodId == null || !KNOWN_MINT_SELECTORS.contains(methodId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Document explorer = rawData.get("explorer", Document.class);
        if (explorer != null) {
            List<Document> tokenTransfers = explorer.getList("tokenTransfers", Document.class);
            if (tokenTransfers != null && !tokenTransfers.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean applyNftMint(NormalizedTransaction tx, Instant now) {
        boolean changed = false;
        if (tx.getType() != NormalizedTransactionType.NFT_MINT) {
            tx.setType(NormalizedTransactionType.NFT_MINT);
            changed = true;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() < 0) {
                if (flow.getRole() != NormalizedLegRole.SELL) {
                    flow.setRole(NormalizedLegRole.SELL);
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
