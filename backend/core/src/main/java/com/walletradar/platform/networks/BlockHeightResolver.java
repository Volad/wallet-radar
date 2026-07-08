package com.walletradar.platform.networks;

import com.walletradar.domain.common.NetworkId;

/**
 * Resolves current block height for a network. Used by backfill package to compute backfill range.
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
