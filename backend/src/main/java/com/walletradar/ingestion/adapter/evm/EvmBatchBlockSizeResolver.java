package com.walletradar.ingestion.adapter.evm;

import com.walletradar.domain.NetworkId;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EvmBatchBlockSizeResolver {

    public static final int DEFAULT_BATCH_BLOCK_SIZE = 2000;
    public static final int MAX_BATCH_BLOCK_SIZE = 10_000;

    private final IngestionNetworkProperties properties;

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
            log.warn("EVM batch-block-size for {} is invalid ({}); min=1, max={}. Using default {}.",
                    networkId, value, MAX_BATCH_BLOCK_SIZE, DEFAULT_BATCH_BLOCK_SIZE);
            return DEFAULT_BATCH_BLOCK_SIZE;
        }
        return value;
    }
}
