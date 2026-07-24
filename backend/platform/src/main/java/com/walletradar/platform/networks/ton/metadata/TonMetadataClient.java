package com.walletradar.platform.networks.ton.metadata;

import java.util.Optional;

/**
 * Free-tier TON Center client for live jetton-master metadata resolution (WS-7).
 *
 * <p>Best-effort by contract: implementations must never throw. Any venue error (rate limit,
 * timeout, 4xx/5xx, malformed body) resolves to {@link Optional#empty()} so callers fall back
 * gracefully (descriptor override / cache / explicit unresolved).</p>
 */
public interface TonMetadataClient {

    /**
     * Resolves a single jetton master's metadata via the TON Center jetton-masters API.
     *
     * @param jettonMaster jetton master address in any canonical form
     * @return metadata when resolvable, else {@link Optional#empty()}
     */
    Optional<TonJettonMetadata> fetchJettonMetadata(String jettonMaster);

    /** Minimal jetton metadata subset used by WalletRadar; either field may be {@code null}. */
    record TonJettonMetadata(String symbol, Integer decimals) {
    }
}
