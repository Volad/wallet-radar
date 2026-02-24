package com.walletradar.ingestion.adapter;

import com.walletradar.domain.NetworkId;

/**
 * Resolves current block height for a network. Used by BackfillJobRunner to compute backfill range.
 */
public interface BlockHeightResolver {

    boolean supports(NetworkId networkId);

    /**
     * Current block number (inclusive) for the given network.
     *
     * @throws RpcException if RPC fails or network not supported
     */
    long getCurrentBlock(NetworkId networkId);
}
