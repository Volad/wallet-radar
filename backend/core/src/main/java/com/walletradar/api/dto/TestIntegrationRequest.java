package com.walletradar.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/sessions/{sessionId}/integrations/test request body.
 */
public record TestIntegrationRequest(
        @NotBlank String provider,
        @NotBlank String apiKey,
        @NotBlank String apiSecret
) {
}
