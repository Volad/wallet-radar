package com.walletradar.platform.networks.solana.jupiter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Free-tier Jupiter (Solana) API client for SPL token metadata resolution and SPL USD pricing.
 *
 * <p>Best-effort by contract: implementations must never throw. Any venue error (rate limit,
 * timeout, 4xx/5xx, malformed body) resolves to {@link Optional#empty()} / an empty map so callers
 * can fall back gracefully (mint-as-symbol, unpriced).</p>
 */
public interface JupiterClient {

    /**
     * Resolves a single SPL mint's metadata via the Jupiter Tokens API.
     *
     * @param mint base58 SPL mint address (case-sensitive)
     * @return metadata when resolvable, else {@link Optional#empty()}
     */
    Optional<JupiterTokenMetadata> fetchTokenMetadata(String mint);

    /**
     * Fetches current USD prices for the given mints via the Jupiter Price v3 API. The caller is
     * responsible for chunking to the venue batch cap ({@link JupiterProperties#getMaxIdsPerRequest()}).
     *
     * @param mints base58 SPL mint addresses (case-sensitive)
     * @return map of mint → USD price for the mints Jupiter could price; never null
     */
    Map<String, BigDecimal> fetchPrices(List<String> mints);

    /** Minimal SPL token metadata subset used by WalletRadar. */
    record JupiterTokenMetadata(String symbol, Integer decimals, String name) {
    }
}
