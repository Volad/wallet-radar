package com.walletradar.application.lp.v4;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Durable, write-once Mongo cache for Uniswap V4 / Pancake Infinity pool state
 * ({@code sqrtPriceX96}) at a specific historical block.
 *
 * <p>Key: {@code (networkId, poolId, blockNumber)}. Populated on first normalization pass.
 * Subsequent rebuilds read from Mongo and skip the archive RPC, ensuring byte-stable fee/principal
 * splits across rebuilds (even if the archive node is unavailable later).
 *
 * <p>The {@code resolved} flag is {@code false} when the archive RPC was called but returned
 * no usable result (node pruned / unavailable). This UNRESOLVED marker is also write-once:
 * once a block-level lookup failed it stays as {@code fee = 0} for that block. Re-resolution
 * is only possible via explicit cache eviction (manual admin action).
 */
@Document(collection = "v4_pool_state_cache")
@NoArgsConstructor
@Getter
@Setter
public class V4PoolStateCache {

    /** Composite key: {@code networkId|poolId|blockNumber}. */
    @Id
    private String cacheKey;

    private String networkId;
    private String poolId;
    private long blockNumber;

    /**
     * {@code sqrtPriceX96} from PoolManager {@code getSlot0} at {@code blockNumber}.
     * {@code null} when {@code resolved = false} (UNRESOLVED sentinel row).
     */
    private BigInteger sqrtPriceX96;

    /**
     * {@code true} when a valid {@code sqrtPriceX96 > 0} was stored.
     * {@code false} for the UNRESOLVED sentinel row (RPC failed — fall back to fee = 0).
     */
    private boolean resolved;

    private Instant createdAt;

    public static String key(String networkId, String poolId, long blockNumber) {
        return networkId + "|" + poolId + "|" + blockNumber;
    }
}
