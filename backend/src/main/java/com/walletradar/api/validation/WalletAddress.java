package com.walletradar.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Valid wallet address: EVM (0x + 40 hex) or Solana (Base58, 32–44 chars).
 * Error code for API: INVALID_ADDRESS.
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = WalletAddressValidator.class)
public @interface WalletAddress {

    String message() default "INVALID_ADDRESS";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
