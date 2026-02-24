package com.walletradar.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Non-empty list of supported NetworkId values.
 * Error code for API: INVALID_NETWORK.
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = SupportedNetworksValidator.class)
public @interface SupportedNetworks {

    String message() default "INVALID_NETWORK";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
