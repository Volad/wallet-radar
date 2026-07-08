package com.walletradar.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates UUID sessionId for /sessions API.
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = SessionIdValidator.class)
public @interface SessionId {

    String message() default "INVALID_SESSION_ID";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
