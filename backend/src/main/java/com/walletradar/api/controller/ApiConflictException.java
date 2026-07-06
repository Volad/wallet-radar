package com.walletradar.api.controller;

/**
 * API-level conflict with explicit error code for ErrorBody mapping.
 */
public class ApiConflictException extends RuntimeException {

    private final String errorCode;

    public ApiConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
