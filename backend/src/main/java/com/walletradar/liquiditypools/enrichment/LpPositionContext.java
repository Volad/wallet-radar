package com.walletradar.liquiditypools.enrichment;

import com.walletradar.domain.common.NetworkId;

import java.time.Instant;

public record LpPositionContext(
        String correlationId,
        String universeId,
        NetworkId networkId,
        String walletAddress,
        String protocol,
        String family,
        String nfpmContract,
        String poolContract,
        String tokenId,
        String lpTokenContract,
        String marketSlug,
        boolean closed,
        boolean staked,
        /** Timestamp of the first LP_ENTRY_REQUEST or LP_ENTRY_SETTLEMENT, used to anchor fee accumulator reads. */
        Instant entryAt
) {
}
