package com.walletradar.costbasis.override;

import lombok.Getter;

/**
 * Thrown by OverrideService when request is invalid or business rule violated.
 * API layer (OverrideController) maps to 404 EVENT_NOT_FOUND, 409 OVERRIDE_EXISTS per 04-api.
 */
@Getter
public class OverrideServiceException extends RuntimeException {

    /** Error code per 04-api: EVENT_NOT_FOUND, OVERRIDE_EXISTS. */
    private final String errorCode;

    public OverrideServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
