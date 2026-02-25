package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 1 (ADR-020): Fetch from RPC → upsert to raw_transactions.
 * Idempotent on (txHash, networkId). Adapter sets walletAddress, classificationStatus, createdAt.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RawFetchSegmentProcessor {

    private final RawTransactionRepository rawTransactionRepository;

    /**
     * Process one block-range segment: fetch from RPC, upsert each RawTransaction.
     *
     * @param walletAddress   wallet to fetch for
     * @param networkId       network
     * @param adapter         network adapter (EVM or Solana)
     * @param segFromBlock    segment start (block or slot)
     * @param segToBlock      segment end
     * @param batchSize       adapter batch size
     * @param processedBlocks shared counter for progress
     * @param totalBlocks     total blocks in backfill
     * @param progressCallback progress reporter
     */
    public void processSegment(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                               long segFromBlock, long segToBlock, int batchSize,
                               AtomicLong processedBlocks, long totalBlocks,
                               BackfillProgressCallback progressCallback) {
        long start = segFromBlock;
        while (start <= segToBlock) {
            long end = Math.min(start + batchSize - 1, segToBlock);
            List<RawTransaction> batch = adapter.fetchTransactions(walletAddress, networkId, start, end);
            for (RawTransaction tx : batch) {
                ensureId(tx);
                rawTransactionRepository.save(tx);
            }
            long blocksInBatch = end - start + 1;
            long totalProcessed = processedBlocks.addAndGet(blocksInBatch);
            int progressPct = totalBlocks <= 0 ? 100 : (int) Math.min(100, (totalProcessed * 100) / totalBlocks);
            progressCallback.reportProgress(progressPct, end,
                    "Raw fetch " + networkId.name() + ": " + progressPct + "% complete");
            start = end + 1;
        }
    }

    private static void ensureId(RawTransaction tx) {
        if (tx.getId() == null || tx.getId().isBlank()) {
            tx.setId(tx.getTxHash() + ":" + tx.getNetworkId());
        }
    }
}
