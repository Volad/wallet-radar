package com.walletradar.api.dto;

/**
 * PUT /api/v1/sessions/{sessionId}/integrations/bybit response.
 */
public record UpsertBybitIntegrationResponse(
        String integrationId,
        String provider,
        String status,
        String displayName,
        String accountRef,
        String maskedKey,
        String message
) {
}
