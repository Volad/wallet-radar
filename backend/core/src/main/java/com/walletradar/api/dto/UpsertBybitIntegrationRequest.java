package com.walletradar.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PUT /api/v1/sessions/{sessionId}/integrations/bybit request body.
 */
public record UpsertBybitIntegrationRequest(
        String displayName,
        @NotBlank String apiKey,
        @NotBlank String apiSecret
) {
}
