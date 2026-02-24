package com.walletradar.ingestion.classifier;

import java.util.Optional;

/**
 * Resolves protocol name by contract address (EVM) or program id (Solana).
 * Used by classifiers to set protocolName on raw events.
 */
public interface ProtocolRegistry {

    /**
     * Look up protocol display name for the given address (case-insensitive for EVM).
     *
     * @param addressOrProgramId contract address (0x…) or Solana program id
     * @return protocol name if known, e.g. "Uniswap V3", "Aave V3"
     */
    Optional<String> getProtocolName(String addressOrProgramId);
}
