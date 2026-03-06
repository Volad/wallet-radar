package com.walletradar.api.dto;

/**
 * POST /api/v1/sessions 202 response.
 */
public record AddSessionResponse(String sessionId, String message) {
}
