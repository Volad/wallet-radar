package com.walletradar.api.dto;

/**
 * GET /api/v1/wallets/{address}/status response (single network).
 */
public record WalletStatusResponse(
        String walletAddress,
        String networkId,
        String status,
        Integer progressPct,
        Long lastBlockSynced,
        Boolean backfillComplete,
        String syncBannerMessage
) {
}
