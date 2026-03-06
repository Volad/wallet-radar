package com.walletradar.api.dto;

import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/backfill-status response.
 */
public record SessionBackfillStatusResponse(
        String sessionId,
        String status,
        Integer overallProgressPct,
        Integer totalTargets,
        Integer completedTargets,
        List<SessionWalletBackfillStatus> wallets
) {
    public record SessionWalletBackfillStatus(
            String address,
            String label,
            String color,
            List<NetworkBackfillStatus> networks
    ) {
    }

    public record NetworkBackfillStatus(
            String networkId,
            String status,
            Integer progressPct,
            Long lastBlockSynced,
            Boolean backfillComplete,
            String syncBannerMessage
    ) {
    }
}
