package com.walletradar.api.validation;

import com.walletradar.domain.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AddressValidatorTest {

    private final AddressValidator validator = new AddressValidator();

    @Test
    @DisplayName("Valid EVM address accepted")
    void validEvmAddress() {
        assertThat(validator.isValidAddress("0x742d35Cc6634C0532925a3b844Bc454e4438f44e")).isTrue();
        assertThat(validator.isValidAddress("0x0000000000000000000000000000000000000000")).isTrue();
    }

    @Test
    @DisplayName("Invalid address rejected")
    void invalidAddress() {
        assertThat(validator.isValidAddress(null)).isFalse();
        assertThat(validator.isValidAddress("")).isFalse();
        assertThat(validator.isValidAddress("0x123")).isFalse();
        assertThat(validator.isValidAddress("nothex")).isFalse();
    }

    @Test
    @DisplayName("Valid networks accepted")
    void validNetworks() {
        assertThat(validator.areValidNetworks(List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))).isTrue();
    }

    @Test
    @DisplayName("Empty or null networks rejected")
    void invalidNetworks() {
        assertThat(validator.areValidNetworks(null)).isFalse();
        assertThat(validator.areValidNetworks(List.of())).isFalse();
    }
}
