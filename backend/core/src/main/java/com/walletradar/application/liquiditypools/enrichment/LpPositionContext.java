package com.walletradar.application.liquiditypools.enrichment;

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
        /**
         * On-chain pool address captured at normalization time for a position-scoped LP row whose
         * pool identity cannot be recovered from the (deallocated) position account later — currently
         * the Meteora DLMM LbPair pool address. When present, {@code SolanaLpPositionReader} uses it
         * directly to resolve the token pair (skipping the position-PDA on-chain decode) for both open
         * and closed positions. {@code null} for every other position.
         */
        String lpPoolAddress,
        boolean closed,
        boolean staked,
        /** Timestamp of the first LP_ENTRY_REQUEST or LP_ENTRY_SETTLEMENT, used to anchor fee accumulator reads. */
        Instant entryAt
) {
}
