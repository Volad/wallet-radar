package com.walletradar.api.dto;

/**
 * POST /api/v1/sessions/{sessionId}/refresh response.
 */
public record SessionRefreshResponse(
        String sessionId,
        String status,
        Integer scheduledTargets,
        Integer skippedTargets,
        String message
) {
}
