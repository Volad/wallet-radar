package com.walletradar.ingestion.adapter;

import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves EVM eth_getLogs batch block size per network from unified config (ADR-012). Unknown or invalid values → global default 2000.
 * See ADR-011 (semantics) and ADR-012 (config layout).
 */
@Component
@RequiredArgsConstructor
public class EvmBatchBlockSizeResolver {

    private static final Logger log = LoggerFactory.getLogger(EvmBatchBlockSizeResolver.class);

    /** Global default when no per-network value or invalid. */
    public static final int DEFAULT_BATCH_BLOCK_SIZE = 2000;

    /** Maximum allowed batch size (cap to avoid RPC timeouts). */
    public static final int MAX_BATCH_BLOCK_SIZE = 10_000;

    private final IngestionNetworkProperties properties;

    /**
     * Resolve batch block size for the given network. Unknown networkId or invalid value → default, no failure.
     */
    public int resolve(NetworkId networkId) {
        if (networkId == null) {
            return DEFAULT_BATCH_BLOCK_SIZE;
        }
        IngestionNetworkProperties.NetworkIngestionEntry entry = properties.getNetwork().get(networkId.name());
        if (entry == null || entry.getBatchBlockSize() == null) {
            return DEFAULT_BATCH_BLOCK_SIZE;
        }
        int value = entry.getBatchBlockSize();
        if (value < 1 || value > MAX_BATCH_BLOCK_SIZE) {
            log.warn(
                    "EVM batch-block-size for {} is invalid ({}); min=1, max={}. Using default {}.",
                    networkId, value, MAX_BATCH_BLOCK_SIZE, DEFAULT_BATCH_BLOCK_SIZE);
            return DEFAULT_BATCH_BLOCK_SIZE;
        }
        return value;
    }
}
