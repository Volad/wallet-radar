package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.filter.ScamFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 (ADR-026): Fetch from chain source → upsert to raw_transactions.
 * Idempotent on (txHash, networkId, walletAddress). Adapter sets walletAddress, normalizationStatus, createdAt.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RawFetchSegmentProcessor {

    private static final int UPSERT_FLUSH_SIZE = 500;

    private final MongoTemplate mongoTemplate;
    private final ScamFilter scamFilter;

    /**
     * Process one block-range segment: fetch from RPC, upsert each RawTransaction.
     *
     * @param walletAddress   wallet to fetch for
     * @param networkId       network
     * @param adapter         network adapter (EVM or Solana)
     * @param segFromBlock    segment start (block or slot)
     * @param segToBlock      segment end
     * @param progressCallback segment progress reporter
     */
    public void processSegment(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                               long segFromBlock, long segToBlock,
                               BackfillProgressCallback progressCallback) {
        log.info("Processing segment for wallet {}, network {}, blocks {}-{}",
                walletAddress, networkId, segFromBlock, segToBlock);
        List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, segFromBlock, segToBlock);
        List<RawTransaction> toUpsert = new ArrayList<>(batch.size());
        for (RawTransaction tx : batch) {
            if (scamFilter.shouldDrop(tx)) {
                continue;
            }
            ensureId(tx);
            toUpsert.add(tx);
            if (toUpsert.size() >= UPSERT_FLUSH_SIZE) {
                bulkUpsert(toUpsert);
                toUpsert.clear();
            }
        }
        if (!toUpsert.isEmpty()) {
            bulkUpsert(toUpsert);
        }
        progressCallback.reportProgress(100, segToBlock);
    }

    private static void ensureId(RawTransaction tx) {
        if (tx.getId() == null || tx.getId().isBlank()) {
            tx.setId(tx.getTxHash() + ":" + tx.getNetworkId() + ":" + tx.getWalletAddress());
        }
    }

    private void bulkUpsert(List<RawTransaction> txs) {
        BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RawTransaction.class);
        for (RawTransaction tx : txs) {
            Query query = Query.query(Criteria.where("_id").is(tx.getId()));
            Update update = new Update()
                    .set("txHash", tx.getTxHash())
                    .set("networkId", tx.getNetworkId())
                    .set("syncMethod", tx.getSyncMethod())
                    .set("walletAddress", tx.getWalletAddress())
                    .set("blockNumber", tx.getBlockNumber())
                    .set("slot", tx.getSlot())
                    .set("normalizationStatus", tx.getNormalizationStatus())
                    .set("retryCount", tx.getRetryCount())
                    .set("lastError", tx.getLastError())
                    .set("nextRetryAt", tx.getNextRetryAt())
                    .set("createdAt", tx.getCreatedAt())
                    .set("rawData", tx.getRawData());
            ops.upsert(query, update);
        }
        ops.execute();
    }
}
