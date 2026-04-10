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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fetches raw transactions from chain sources and upserts them into raw_transactions.
 * Idempotent on (txHash, networkId, walletAddress).
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
        persistBatch(batch);
        progressCallback.reportProgress(100, segToBlock);
    }

    /**
     * RPC-safe segment processing with periodic block checkpoints.
     * After each successful sub-range we persist progress, so retries continue from the last checkpoint.
     */
    public void processSegmentWithBlockCheckpoints(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                                                   long segFromBlock, long segToBlock, int checkpointBlockSpan,
                                                   BackfillProgressCallback progressCallback) {
        log.info("Processing segment with checkpoints for wallet {}, network {}, blocks {}-{}, checkpointBlockSpan={}",
                walletAddress, networkId, segFromBlock, segToBlock, checkpointBlockSpan);
        long chunkSize = Math.max(1L, checkpointBlockSpan);
        long from = segFromBlock;
        while (from <= segToBlock) {
            long to = Math.min(segToBlock, from + chunkSize - 1);
            List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, from, to);
            persistBatch(batch);
            progressCallback.reportProgress(100, to);
            from = to + 1;
        }
    }

    private void persistBatch(List<RawTransaction> batch) {
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
    }

    private static void ensureId(RawTransaction tx) {
        if (tx.getId() == null || tx.getId().isBlank()) {
            tx.setId(tx.getTxHash() + ":" + tx.getNetworkId() + ":" + tx.getWalletAddress());
        }
    }

    private void bulkUpsert(List<RawTransaction> txs) {
        Map<String, RawTransaction> existingById = existingTransactionsById(txs);
        BulkOperations ops = null;
        int operationCount = 0;
        int skippedExisting = 0;
        for (RawTransaction tx : txs) {
            Query query = Query.query(Criteria.where("_id").is(tx.getId()));
            RawTransaction existing = existingById.get(tx.getId());
            if (existing == null) {
                if (ops == null) {
                    ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RawTransaction.class);
                }
                ops.upsert(query, insertUpdate(tx));
                operationCount++;
                continue;
            }
            skippedExisting++;
        }
        if (ops != null && operationCount > 0) {
            log.info(
                    "Raw refresh upsert queued {} insert operations out of {} fetched rows; skippedExisting={}",
                    operationCount,
                    txs.size(),
                    skippedExisting
            );
            ops.execute();
        } else {
            log.info("Raw refresh upsert skipped writes for {} fetched rows; skippedExisting={}", txs.size(), skippedExisting);
        }
    }

    private Map<String, RawTransaction> existingTransactionsById(List<RawTransaction> txs) {
        if (txs.isEmpty()) {
            return Map.of();
        }
        List<String> ids = txs.stream()
                .map(RawTransaction::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mongoTemplate.find(Query.query(Criteria.where("_id").in(ids)), RawTransaction.class)
                .stream()
                .collect(Collectors.toMap(RawTransaction::getId, existing -> existing, (left, right) -> left, LinkedHashMap::new));
    }

    private Update insertUpdate(RawTransaction tx) {
        return new Update()
                .setOnInsert("txHash", tx.getTxHash())
                .setOnInsert("networkId", tx.getNetworkId())
                .setOnInsert("syncMethod", tx.getSyncMethod())
                .setOnInsert("walletAddress", tx.getWalletAddress())
                .setOnInsert("blockNumber", tx.getBlockNumber())
                .setOnInsert("slot", tx.getSlot())
                .setOnInsert("rawData", tx.getRawData())
                .setOnInsert("normalizationStatus", tx.getNormalizationStatus())
                .setOnInsert("retryCount", tx.getRetryCount())
                .setOnInsert("lastError", tx.getLastError())
                .setOnInsert("nextRetryAt", tx.getNextRetryAt())
                .setOnInsert("createdAt", tx.getCreatedAt());
    }
}
