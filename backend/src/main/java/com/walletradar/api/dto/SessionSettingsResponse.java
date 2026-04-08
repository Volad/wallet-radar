package com.walletradar.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/sessions/{sessionId}/settings response.
 */
public record SessionSettingsResponse(
        String sessionId,
        List<WalletEntry> wallets,
        List<IntegrationEntry> integrations,
        Boolean hideSmallAssets,
        Boolean showReconciliationWarnings
) {
    public record WalletEntry(
            String address,
            String label,
            String color,
            List<String> networks
    ) {
    }

    public record IntegrationEntry(
            String integrationId,
            String provider,
            String status,
            String displayName,
            String accountRef,
            String maskedKey,
            boolean readOnly,
            List<String> capabilities,
            Instant lastValidatedAt,
            Instant lastSyncAt,
            String lastError,
            int totalSegments,
            int completedSegments,
            int failedSegments,
            int progressPct
    ) {
    }
}
