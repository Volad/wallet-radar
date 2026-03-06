package com.walletradar.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

/**
 * Validates EVM-only wallet addresses for /sessions API.
 */
@Component
public class EvmWalletAddressValidator implements ConstraintValidator<EvmWalletAddress, String> {

    private final AddressValidator addressValidator;

    public EvmWalletAddressValidator(AddressValidator addressValidator) {
        this.addressValidator = addressValidator;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && addressValidator.isValidEvmAddress(value);
    }
}
