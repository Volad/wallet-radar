package com.walletradar.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PUT /api/v1/sessions/{sessionId}/integrations/dzengi request body.
 */
public record UpsertDzengiIntegrationRequest(
        String displayName,
        @NotBlank String apiKey,
        @NotBlank String apiSecret
) {
}
