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
 * Valid EVM wallet address (0x + 40 hex).
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = EvmWalletAddressValidator.class)
public @interface EvmWalletAddress {

    String message() default "INVALID_ADDRESS";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
