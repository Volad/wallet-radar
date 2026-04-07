package com.walletradar.api.controller;

/**
 * API-level bad request with explicit error code for ErrorBody mapping.
 */
public class ApiBadRequestException extends RuntimeException {

    private final String errorCode;

    public ApiBadRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
