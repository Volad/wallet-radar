package com.walletradar.domain;

/**
 * Application event: raw fetch (Phase 1) complete for a (wallet, network).
 * Published by BackfillNetworkExecutor; consumed by RawTransactionClassifierJob (ADR-021).
 */
public record RawFetchCompleteEvent(String walletAddress, String networkId, long lastBlockSynced) {
}
