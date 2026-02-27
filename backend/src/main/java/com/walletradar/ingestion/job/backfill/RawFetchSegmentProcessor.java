package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
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
 * Phase 1 (ADR-020): Fetch from RPC → upsert to raw_transactions.
 * Idempotent on (txHash, networkId). Adapter sets walletAddress, classificationStatus, createdAt.
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
     * @param batchSize       adapter batch size
     * @param progressCallback segment progress reporter
     */
    public void processSegment(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                               long segFromBlock, long segToBlock, int batchSize,
                               BackfillProgressCallback progressCallback) {
        long totalBlocks = segToBlock - segFromBlock + 1;
        long processedBlocks = 0L;
        long start = segFromBlock;
        while (start <= segToBlock) {
            long end = Math.min(start + batchSize - 1, segToBlock);
            List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, start, end);
            List<RawTransaction> toUpsert = new ArrayList<>(batch.size());
            for (RawTransaction tx : batch) {
                if (scamFilter.isScam(tx)) {
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
            long blocksInBatch = end - start + 1;
            processedBlocks += blocksInBatch;
            int progressPct = totalBlocks <= 0 ? 100 : (int) Math.min(100, (processedBlocks * 100) / totalBlocks);
            progressCallback.reportProgress(progressPct, end);
            start = end + 1;
        }
    }

    private static void ensureId(RawTransaction tx) {
        if (tx.getId() == null || tx.getId().isBlank()) {
            tx.setId(tx.getTxHash() + ":" + tx.getNetworkId());
        }
    }

    private void bulkUpsert(List<RawTransaction> txs) {
        BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RawTransaction.class);
        for (RawTransaction tx : txs) {
            Query query = Query.query(Criteria.where("_id").is(tx.getId()));
            Update update = new Update()
                    .set("txHash", tx.getTxHash())
                    .set("networkId", tx.getNetworkId())
                    .set("walletAddress", tx.getWalletAddress())
                    .set("blockNumber", tx.getBlockNumber())
                    .set("slot", tx.getSlot())
                    .set("classificationStatus", tx.getClassificationStatus())
                    .set("createdAt", tx.getCreatedAt())
                    .set("rawData", tx.getRawData());
            ops.upsert(query, update);
        }
        ops.execute();
    }
}
