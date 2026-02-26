package com.walletradar.ingestion.adapter.evm;

import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estimates block timestamps via linear interpolation between two anchor points.
 * Used during backfill where daily-granularity prices make ±seconds irrelevant.
 * For exact timestamps (e.g. incremental sync), use {@link EvmBlockTimestampResolver}.
 *
 * <p>Not a Spring component — instantiated per backfill session in {@link com.walletradar.ingestion.job.backfill.BackfillNetworkExecutor}.
 */
public class EstimatingBlockTimestampResolver {

    private record AnchorData(long fromBlock, Instant fromTimestamp, double avgBlockTimeSeconds) {}

    private final Map<NetworkId, AnchorData> anchors = new ConcurrentHashMap<>();

    /**
     * Calibrate by fetching exact timestamps for two anchor blocks via RPC,
     * then computing the actual average block time for this range.
     *
     * @param fallbackAvgBlockTimeSeconds used when {@code fromBlock == toBlock}
     *                                    (single-block range where division is impossible)
     */
    public void calibrate(NetworkId networkId, long fromBlock, long toBlock,
                          BlockTimestampResolver exactResolver, double fallbackAvgBlockTimeSeconds) {
        Instant fromTs = exactResolver.getBlockTimestamp(networkId, fromBlock);

        if (fromBlock == toBlock) {
            anchors.put(networkId, new AnchorData(fromBlock, fromTs, fallbackAvgBlockTimeSeconds));
            return;
        }

        Instant toTs = exactResolver.getBlockTimestamp(networkId, toBlock);
        double actualAvg = (double) (toTs.getEpochSecond() - fromTs.getEpochSecond()) / (toBlock - fromBlock);
        if (actualAvg <= 0) {
            actualAvg = fallbackAvgBlockTimeSeconds;
        }
        anchors.put(networkId, new AnchorData(fromBlock, fromTs, actualAvg));
    }

    /**
     * Estimate timestamp for any block in the calibrated range.
     * O(1), deterministic, no RPC calls.
     */
    public Instant estimate(NetworkId networkId, long blockNumber) {
        AnchorData anchor = anchors.get(networkId);
        if (anchor == null) {
            throw new IllegalStateException("No calibration data for " + networkId
                    + "; call calibrate() before estimate()");
        }
        long blockDelta = blockNumber - anchor.fromBlock;
        long secondsDelta = Math.round(blockDelta * anchor.avgBlockTimeSeconds);
        return anchor.fromTimestamp.plusSeconds(secondsDelta);
    }

    boolean isCalibrated(NetworkId networkId) {
        return anchors.containsKey(networkId);
    }
}
