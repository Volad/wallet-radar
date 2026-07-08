package com.walletradar.api.controller;

/**
 * API-level not found with explicit error code for ErrorBody mapping.
 */
public class ApiNotFoundException extends RuntimeException {

    private final String errorCode;

    public ApiNotFoundException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
