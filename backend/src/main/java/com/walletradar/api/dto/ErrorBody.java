package com.walletradar.api.dto;

import java.time.Instant;

/**
 * Standard error response body per docs/04-api.md: error (code), message, timestamp (ISO 8601).
 * Used for validation 400, GET status 400, and future 404/5xx.
 */
public record ErrorBody(String error, String message, Instant timestamp) {

    /**
     * Creates an error body with timestamp set to now (UTC).
     */
    public static ErrorBody of(String error, String message) {
        return new ErrorBody(error, message, Instant.now());
    }
}
