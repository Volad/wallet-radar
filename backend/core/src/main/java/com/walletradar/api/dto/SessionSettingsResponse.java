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
        List<ExternalVenueEntry> externalVenues,
        List<ExternalCustodyDestinationEntry> externalCustodyDestinations,
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

    /**
     * Cycle/9 S2: owned external-venue counterparty address (Paradex/MEX/etc.).
     */
    public record ExternalVenueEntry(
            String address,
            String provider,
            String label,
            List<String> networks
    ) {
    }

    /**
     * WS-5 (ADR-072): user-designated external custody destination (labeled counterparty only).
     */
    public record ExternalCustodyDestinationEntry(
            String address,
            String provider,
            String label,
            List<String> networks
    ) {
    }

    public record IntegrationEntry(
            String integrationId,
            String provider,
            String status,
            String displayName,
            String accountRef,
            String color,
            String maskedKey,
            boolean readOnly,
            List<String> capabilities,
            Instant lastValidatedAt,
            Instant lastSyncAt,
            String lastError,
            int totalSegments,
            int completedSegments,
            int failedSegments,
            int progressPct,
            List<StreamSyncEntry> streamSync
    ) {
    }

    /**
     * Bybit-only: last successful segment completion and newest stored extracted row per API stream.
     */
    public record StreamSyncEntry(
            String stream,
            Instant lastSegmentCompletedAt,
            Instant newestStoredEventAt
    ) {
    }
}
