package com.walletradar.liquiditypools.enrichment;

import com.walletradar.domain.common.NetworkId;

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
        boolean staked
) {
}
