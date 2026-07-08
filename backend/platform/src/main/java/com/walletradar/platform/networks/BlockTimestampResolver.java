package com.walletradar.platform.networks;

import com.walletradar.domain.common.NetworkId;

import java.time.Instant;

/**
 * Resolves block timestamp from block number. Used when normalising raw transactions.
 */
public interface BlockTimestampResolver {

    boolean supports(NetworkId networkId);

    /**
     * Block timestamp for the given network and block number.
     *
     * @throws RpcException if RPC fails or network not supported
     */
    Instant getBlockTimestamp(NetworkId networkId, long blockNumber);
}
