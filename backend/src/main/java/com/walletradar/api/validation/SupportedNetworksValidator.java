package com.walletradar.api.validation;

import com.walletradar.domain.NetworkId;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates that the list contains only supported NetworkId values and is non-empty.
 * Delegates to AddressValidator for a single source of truth.
 */
@Component
public class SupportedNetworksValidator implements ConstraintValidator<SupportedNetworks, List<NetworkId>> {

    private final AddressValidator addressValidator;

    public SupportedNetworksValidator(AddressValidator addressValidator) {
        this.addressValidator = addressValidator;
    }

    @Override
    public boolean isValid(List<NetworkId> value, ConstraintValidatorContext context) {
        return addressValidator.areValidNetworks(value);
    }
}
