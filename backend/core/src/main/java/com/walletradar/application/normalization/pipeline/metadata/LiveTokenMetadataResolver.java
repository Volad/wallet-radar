package com.walletradar.application.normalization.pipeline.metadata;

import com.walletradar.domain.common.NetworkId;

import java.util.Optional;

/**
 * SPI for a per-network live token-metadata resolver (WS-7). Implementations perform the network
 * call for a single identity; the durable caching, descriptor precedence and write-through are owned
 * by {@link TokenMetadataResolutionService}, so an implementation must NOT consult the descriptor or
 * the persistent cache itself (that would risk recursion through the resolution bridge).
 *
 * <p>Best-effort by contract: never throws; unresolved identities resolve to {@link Optional#empty()}.</p>
 */
public interface LiveTokenMetadataResolver {

    boolean supports(NetworkId networkId);

    Optional<ResolvedTokenMetadata> resolve(NetworkId networkId, String contract);
}
