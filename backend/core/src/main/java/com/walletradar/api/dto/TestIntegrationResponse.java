package com.walletradar.api.dto;

/**
 * POST /api/v1/sessions/{sessionId}/integrations/test response body.
 */
public record TestIntegrationResponse(
        String provider,
        String userId,
        boolean readOnly,
        String message
) {
}
