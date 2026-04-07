package com.walletradar.api.dto;

import java.time.Instant;

/**
 * Standard API error response body.
 */
public record ErrorBody(String error, String message, Instant timestamp) {

    /**
     * Creates an error body with timestamp set to now (UTC).
     */
    public static ErrorBody of(String error, String message) {
        return new ErrorBody(error, message, Instant.now());
    }
}
