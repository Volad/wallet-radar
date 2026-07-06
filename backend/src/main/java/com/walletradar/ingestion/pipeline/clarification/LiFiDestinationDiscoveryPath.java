package com.walletradar.ingestion.pipeline.clarification;

/**
 * Deterministic evidence path used when ingesting an official LI.FI receiving transaction.
 */
public enum LiFiDestinationDiscoveryPath {
    WALLET_TOUCH,
    LIFI_CALLDATA,
    TRACE
}
