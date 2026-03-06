package com.walletradar.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validates #RRGGBB color value.
 */
@Component
public class HexColorValidator implements ConstraintValidator<HexColor, String> {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && HEX_COLOR.matcher(value.trim()).matches();
    }
}
