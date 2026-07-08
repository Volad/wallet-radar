package com.walletradar.api.controller;

import com.walletradar.api.dto.ErrorBody;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.Optional;

/**
 * Maps validation failures (@Valid) to 400 with ErrorBody (error, message, timestamp) per 04-api.
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(ApiBadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorBody handleBadRequest(ApiBadRequestException ex) {
        return ErrorBody.of(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(ApiNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorBody handleNotFound(ApiNotFoundException ex) {
        return ErrorBody.of(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(ApiConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorBody handleConflict(ApiConflictException ex) {
        return ErrorBody.of(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorBody handleValidation(WebExchangeBindException ex) {
        String error = Optional.ofNullable(ex.getFieldError())
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .orElse("VALIDATION_ERROR");
        String message = userFacingMessage(error, ex);
        return ErrorBody.of(error, message);
    }

    private static String userFacingMessage(String errorCode, WebExchangeBindException ex) {
        return switch (errorCode) {
            case "INVALID_ADDRESS" -> "Invalid wallet address format";
            case "INVALID_NETWORK" -> "One or more network IDs are not supported";
            case "INVALID_SESSION_ID" -> "Invalid session ID format";
            case "INVALID_LABEL" -> "Wallet label is required";
            case "INVALID_COLOR" -> "Wallet color must be in #RRGGBB format";
            case "INVALID_REQUEST" -> "Invalid request payload";
            default -> ex.getFieldErrors().stream()
                    .findFirst()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .orElse("Validation failed");
        };
    }
}
