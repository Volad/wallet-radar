package com.walletradar.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

/**
 * Validates wallet address format (EVM or Solana) for Jakarta Bean Validation.
 * Delegates to AddressValidator for a single source of truth.
 */
@Component
public class WalletAddressValidator implements ConstraintValidator<WalletAddress, String> {

    private final AddressValidator addressValidator;

    public WalletAddressValidator(AddressValidator addressValidator) {
        this.addressValidator = addressValidator;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && addressValidator.isValidAddress(value);
    }
}
