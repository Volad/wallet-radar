package com.walletradar.api.dto;

/**
 * PUT /api/v1/sessions/{sessionId}/integrations/dzengi response body.
 */
public record UpsertDzengiIntegrationResponse(
        String integrationId,
        String provider,
        String status,
        String displayName,
        String accountRef,
        String maskedKey,
        String message
) {
}
