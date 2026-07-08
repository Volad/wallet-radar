package com.walletradar.platform.networks;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;

import java.util.List;

/**
 * Fetches raw transaction data for a wallet×network in a block range.
 * Batch size contract: EVM uses 2000-block batches; implementors must respect network limits.
 */
public interface NetworkAdapter {

    /**
     * Whether this adapter supports the given network.
     */
    boolean supports(NetworkId networkId);

    /**
     * Fetch raw transactions for the wallet on the given network from fromBlock (inclusive) to toBlock (inclusive).
     * Implementations split into internal batches (e.g. 2000 blocks for EVM) and aggregate results.
     */
    List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId, long fromBlock, long toBlock);

    /**
     * Maximum block range per single RPC call (e.g. 2000 for EVM eth_getLogs).
     */
    int getMaxBlockBatchSize();

    /**
     * Whether the backfill executor may split one segment into multiple block checkpoints.
     * Provider-first adapters can disable this to guarantee exactly one fetch pass per segment.
     */
    default boolean supportsBlockCheckpointing() {
        return true;
    }
}
