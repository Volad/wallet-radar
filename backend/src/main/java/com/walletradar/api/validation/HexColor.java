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
 * Hex color in #RRGGBB format.
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = HexColorValidator.class)
public @interface HexColor {

    String message() default "INVALID_COLOR";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
