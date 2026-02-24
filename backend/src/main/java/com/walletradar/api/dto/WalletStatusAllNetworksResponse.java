package com.walletradar.api.dto;

import java.util.List;

/**
 * GET /api/v1/wallets/{address}/status (all networks) response.
 */
public record WalletStatusAllNetworksResponse(String walletAddress, List<NetworkStatusEntry> networks) {

    public record NetworkStatusEntry(
            String networkId,
            String status,
            Integer progressPct,
            Long lastBlockSynced,
            Boolean backfillComplete,
            String syncBannerMessage
    ) {}
}
