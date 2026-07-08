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
 * Non-empty list of supported EVM network IDs.
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = SupportedEvmNetworksValidator.class)
public @interface SupportedEvmNetworks {

    String message() default "INVALID_NETWORK";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
