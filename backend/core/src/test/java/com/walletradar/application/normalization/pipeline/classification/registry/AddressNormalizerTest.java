package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressNormalizerTest {

    @Test
    @DisplayName("EVM addresses are lowercased and 0x-normalized (behaviour unchanged)")
    void evmAddressesAreLowercased() {
        assertThat(AddressNormalizer.normalize(NetworkId.ETHEREUM, "0xBA12222222228d8Ba445958a75a0704d566BF2C8"))
                .isEqualTo("0xba12222222228d8ba445958a75a0704d566bf2c8");
        assertThat(AddressNormalizer.normalizeForEntry(Set.of(NetworkId.ARBITRUM), "0xABCabc0000000000000000000000000000000000"))
                .isEqualTo("0xabcabc0000000000000000000000000000000000");
    }

    @Test
    @DisplayName("Solana program IDs preserve case-sensitive base58 verbatim")
    void solanaProgramIdsPreserveBase58() {
        String jupiter = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
        assertThat(AddressNormalizer.normalize(NetworkId.SOLANA, jupiter)).isEqualTo(jupiter);
        assertThat(AddressNormalizer.normalizeForEntry(Set.of(NetworkId.SOLANA), jupiter)).isEqualTo(jupiter);
    }

    @Test
    @DisplayName("Solana normalization rejects non-base58 / EVM-shaped values")
    void solanaRejectsNonBase58() {
        assertThat(AddressNormalizer.normalizeSolana("0x1234")).isNull();
        assertThat(AddressNormalizer.normalizeSolana("not a valid mint!")).isNull();
        assertThat(AddressNormalizer.normalizeSolana(null)).isNull();
    }

    @Test
    @DisplayName("rejects an entry mixing Solana and EVM networks")
    void rejectsMixedNetworks() {
        assertThatThrownBy(() -> AddressNormalizer.normalizeForEntry(
                Set.of(NetworkId.ETHEREUM, NetworkId.SOLANA),
                "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
        )).isInstanceOf(IllegalStateException.class);
    }
}
