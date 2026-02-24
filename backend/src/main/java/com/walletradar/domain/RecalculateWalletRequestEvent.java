package com.walletradar.domain;

/**
 * Application event: request AVCO recalc for a wallet (e.g. after backfill or reclassify).
 * Published by ingestion; consumed by costbasis.
 */
public record RecalculateWalletRequestEvent(String walletAddress) {
}
