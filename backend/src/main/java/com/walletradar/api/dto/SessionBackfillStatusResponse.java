package com.walletradar.api.dto;

import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/backfill-status response.
 */
public record SessionBackfillStatusResponse(
        String sessionId,
        String status,
        String acquisitionStatus,
        Integer overallProgressPct,
        Integer totalTargets,
        Integer completedTargets,
        String pipelineStage,
        String pipelineStatus,
        String pipelineMessage,
        PhaseProgress phaseProgress,
        List<SessionWalletBackfillStatus> wallets
) {
    public record PhaseProgress(
            String phase,
            Integer progressPct,
            Long processedCount,
            Long leftCount,
            Long totalCount
    ) {
    }

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
