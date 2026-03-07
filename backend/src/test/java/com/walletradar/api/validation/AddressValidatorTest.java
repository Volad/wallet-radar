package com.walletradar.api.validation;

import com.walletradar.domain.common.NetworkId;
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
        assertThat(validator.isValidEvmAddress("0x742d35Cc6634C0532925a3b844Bc454e4438f44e")).isTrue();
    }

    @Test
    @DisplayName("Invalid address rejected")
    void invalidAddress() {
        assertThat(validator.isValidAddress(null)).isFalse();
        assertThat(validator.isValidAddress("")).isFalse();
        assertThat(validator.isValidAddress("0x123")).isFalse();
        assertThat(validator.isValidAddress("nothex")).isFalse();
        assertThat(validator.isValidEvmAddress("8B8f12aC07E9746e9B053B8D7EF1d45270D693f")).isFalse();
    }

    @Test
    @DisplayName("Valid networks accepted")
    void validNetworks() {
        assertThat(validator.areValidNetworks(List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))).isTrue();
    }

    @Test
    @DisplayName("Empty or null networks accepted (means all networks)")
    void emptyOrNullNetworksAccepted() {
        assertThat(validator.areValidNetworks(null)).isTrue();
        assertThat(validator.areValidNetworks(List.of())).isTrue();
    }

    @Test
    @DisplayName("EVM networks validator rejects SOLANA and empty sets")
    void evmNetworksValidation() {
        assertThat(validator.areValidEvmNetworks(List.of(NetworkId.ETHEREUM, NetworkId.ARBITRUM))).isTrue();
        assertThat(validator.areValidEvmNetworks(List.of(NetworkId.LINEA))).isTrue();
        assertThat(validator.areValidEvmNetworks(List.of(NetworkId.UNICHAIN))).isTrue();
        assertThat(validator.areValidEvmNetworks(List.of(NetworkId.ZKSYNC))).isTrue();
        assertThat(validator.areValidEvmNetworks(List.of(NetworkId.SOLANA))).isFalse();
        assertThat(validator.areValidEvmNetworks(List.of())).isFalse();
        assertThat(validator.areValidEvmNetworks(null)).isFalse();
    }
}
