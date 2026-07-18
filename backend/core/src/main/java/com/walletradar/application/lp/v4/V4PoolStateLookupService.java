package com.walletradar.application.lp.v4;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.platform.networks.evm.rpc.V4PoolStateReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Lookup service for V4 pool {@code sqrtPriceX96} with durable Mongo persistence.
 *
 * <p>Read path: Mongo first (write-once cache) → fall back to {@link V4PoolStateReader} (RPC) →
 * persist result (or UNRESOLVED sentinel) to Mongo for future rebuilds.
 *
 * <p>The write-once invariant: once a {@code cacheKey} row exists in {@code v4_pool_state_cache},
 * neither the resolved price nor the UNRESOLVED marker is ever overwritten. This guarantees that
 * the fee/principal split for a given block remains byte-stable across all rebuild runs, even when
 * the archive node is no longer available for that block.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class V4PoolStateLookupService {

    private final V4PoolStateCacheRepository repository;
    private final V4PoolStateReader reader;

    /**
     * Returns {@code sqrtPriceX96} for the given pool at the given block, or
     * {@link Optional#empty()} when neither the Mongo cache nor the archive RPC can supply a value.
     *
     * @param network     EVM network
     * @param poolId      hex pool ID (32 bytes, with or without 0x prefix)
     * @param blockNumber block number at which to read the price
     */
    public Optional<BigInteger> getSqrtPriceX96(NetworkId network, String poolId, long blockNumber) {
        if (network == null || poolId == null || poolId.isBlank() || blockNumber <= 0) {
            return Optional.empty();
        }
        String normalizedPoolId = normalizeHex(poolId);
        String cacheKey = V4PoolStateCache.key(network.name(), normalizedPoolId, blockNumber);

        // 1. Check Mongo cache (write-once: if row exists, honour it even if UNRESOLVED)
        Optional<V4PoolStateCache> cached = repository.findById(cacheKey);
        if (cached.isPresent()) {
            V4PoolStateCache entry = cached.get();
            if (!entry.isResolved()) {
                return Optional.empty();
            }
            return Optional.ofNullable(entry.getSqrtPriceX96());
        }

        // 2. Mongo cache miss → try RPC
        Optional<BigInteger> rpcResult = reader.getSqrtPriceX96(network, normalizedPoolId, blockNumber);

        // 3. Persist result (or UNRESOLVED sentinel) — write-once
        persist(cacheKey, network, normalizedPoolId, blockNumber, rpcResult.orElse(null));

        return rpcResult;
    }

    private void persist(
            String cacheKey,
            NetworkId network,
            String poolId,
            long blockNumber,
            BigInteger sqrtPriceX96
    ) {
        try {
            // Guard: only insert if absent (write-once — another thread may have raced us)
            if (repository.existsById(cacheKey)) {
                return;
            }
            V4PoolStateCache entry = new V4PoolStateCache();
            entry.setCacheKey(cacheKey);
            entry.setNetworkId(network.name());
            entry.setPoolId(poolId);
            entry.setBlockNumber(blockNumber);
            entry.setSqrtPriceX96(sqrtPriceX96);
            entry.setResolved(sqrtPriceX96 != null && sqrtPriceX96.signum() > 0);
            entry.setCreatedAt(Instant.now());
            repository.save(entry);
            log.debug("V4 pool state cache: persisted network={} poolId={} block={} resolved={}",
                    network, poolId, blockNumber, entry.isResolved());
        } catch (Exception ex) {
            // Non-fatal: in-memory result already returned; Mongo failure only affects rebuild determinism
            log.warn("V4 pool state cache: failed to persist network={} poolId={} block={}: {}",
                    network, poolId, blockNumber, ex.getMessage());
        }
    }

    private static String normalizeHex(String hex) {
        if (hex == null) return "";
        String clean = hex.trim().toLowerCase(Locale.ROOT);
        return clean.startsWith("0x") ? clean.substring(2) : clean;
    }
}
