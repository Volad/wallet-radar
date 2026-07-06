package com.walletradar.api.dto;

/**
 * DELETE /api/v1/sessions/{sessionId}/integrations/{integrationId} response.
 */
public record DeleteIntegrationResponse(
        String integrationId,
        String message
) {
}
