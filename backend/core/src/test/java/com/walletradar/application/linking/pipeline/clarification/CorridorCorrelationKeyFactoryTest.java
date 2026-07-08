package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-9 WS-1 / D1. The corridor correlation triple must be a pure, idempotent, order-stable
 * function of {@code (networkId, canonicalTxHash)} (T-9 corrId-set core, T-10 idempotency).
 */
class CorridorCorrelationKeyFactoryTest {

    private static final String EVM_HASH = "0xBC3FE1A56B06077185272A29BEB16FDA87FCF4C26049F0D6E13785A6B658CE27";
    private static final String SOL_SIG = "3xKzVeUuM7g5q5Z2nNuG6XJoYwYz5Z9Wxq8KkFaWQRrQRSyDpL77UvBASE58CaseSensitive";

    @Test
    @DisplayName("T-9: EVM corridor key lower-cases the hash and retains the BYBIT-CORRIDOR prefix")
    void evmCorridorKeyIsCanonicalLowerCase() {
        String key = CorridorCorrelationKeyFactory.corridorKey(NetworkId.ARBITRUM, EVM_HASH);
        assertThat(key).isEqualTo("BYBIT-CORRIDOR:ARBITRUM:" + EVM_HASH.toLowerCase());
    }

    @Test
    @DisplayName("T-9: Solana corridor key keeps the case-sensitive base58 signature")
    void solanaCorridorKeyIsCaseSensitive() {
        String key = CorridorCorrelationKeyFactory.corridorKey(NetworkId.SOLANA, SOL_SIG);
        assertThat(key).isEqualTo("BYBIT-CORRIDOR:SOLANA:" + SOL_SIG);
    }

    @Test
    @DisplayName("T-10: repeated derivation is bit-identical and casing-insensitive on EVM input")
    void derivationIsIdempotentAndOrderStable() {
        String first = CorridorCorrelationKeyFactory.corridorKey(NetworkId.MANTLE, EVM_HASH);
        String second = CorridorCorrelationKeyFactory.corridorKey(NetworkId.MANTLE, EVM_HASH.toLowerCase());
        String third = CorridorCorrelationKeyFactory.corridorKey(NetworkId.MANTLE, "  " + EVM_HASH + "  ");
        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(CorridorCorrelationKeyFactory.isCorridorKey(first)).isTrue();
    }

    @Test
    @DisplayName("null / blank inputs yield no corridor key")
    void invalidInputsReturnNull() {
        assertThat(CorridorCorrelationKeyFactory.corridorKey(null, EVM_HASH)).isNull();
        assertThat(CorridorCorrelationKeyFactory.corridorKey(NetworkId.ARBITRUM, "")).isNull();
        assertThat(CorridorCorrelationKeyFactory.corridorKey(NetworkId.ARBITRUM, "   ")).isNull();
        assertThat(CorridorCorrelationKeyFactory.isCorridorKey(null)).isFalse();
        assertThat(CorridorCorrelationKeyFactory.isCorridorKey("internal-tx:arbitrum:0xabc")).isFalse();
    }

    @Test
    @DisplayName("Bybit sub-account endpoint defaults to :FUND and preserves an explicit suffix")
    void bybitSubAccountEndpointIsCanonical() {
        assertThat(CorridorCorrelationKeyFactory.bybitSubAccountEndpoint("BYBIT:33625378"))
                .isEqualTo("BYBIT:33625378:FUND");
        assertThat(CorridorCorrelationKeyFactory.bybitSubAccountEndpoint("BYBIT:33625378:earn"))
                .isEqualTo("BYBIT:33625378:EARN");
        assertThat(CorridorCorrelationKeyFactory.bybitSubAccountEndpoint("0xabc")).isNull();
        assertThat(CorridorCorrelationKeyFactory.bybitSubAccountEndpoint(null)).isNull();
    }
}
