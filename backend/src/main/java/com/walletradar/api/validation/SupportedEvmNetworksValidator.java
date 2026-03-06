package com.walletradar.api.validation;

import com.walletradar.domain.common.NetworkId;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates that all provided networks are from supported EVM set.
 */
@Component
public class SupportedEvmNetworksValidator implements ConstraintValidator<SupportedEvmNetworks, List<NetworkId>> {

    private final AddressValidator addressValidator;

    public SupportedEvmNetworksValidator(AddressValidator addressValidator) {
        this.addressValidator = addressValidator;
    }

    @Override
    public boolean isValid(List<NetworkId> value, ConstraintValidatorContext context) {
        return addressValidator.areValidEvmNetworks(value);
    }
}
