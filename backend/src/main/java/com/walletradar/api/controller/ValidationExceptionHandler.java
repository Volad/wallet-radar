package com.walletradar.api.controller;

import com.walletradar.api.dto.ErrorBody;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.Optional;

/**
 * Maps validation failures (@Valid) to 400 with ErrorBody (error, message, timestamp) per 04-api.
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorBody> handleValidation(WebExchangeBindException ex) {
        String error = Optional.ofNullable(ex.getFieldError())
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .orElse("VALIDATION_ERROR");
        String message = userFacingMessage(error, ex);
        return ResponseEntity.badRequest().body(ErrorBody.of(error, message));
    }

    private static String userFacingMessage(String errorCode, WebExchangeBindException ex) {
        return switch (errorCode) {
            case "INVALID_ADDRESS" -> "Invalid wallet address format";
            case "INVALID_NETWORK" -> "One or more network IDs are not supported";
            default -> ex.getFieldErrors().stream()
                    .findFirst()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .orElse("Validation failed");
        };
    }
}
