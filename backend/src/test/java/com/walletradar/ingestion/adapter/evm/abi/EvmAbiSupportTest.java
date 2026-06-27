package com.walletradar.ingestion.adapter.evm.abi;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvmAbiSupportTest {

    @Test
    void selectorMatchesKnownSignature() {
        assertThat(EvmAbiSupport.selector("balanceOf(address)")).isEqualTo("70a08231");
    }

    @Test
    void addressFromWordExtractsTrailing20Bytes() {
        String word = "000000000000000000000000abc123def4567890123456789012345678901234";
        assertThat(EvmAbiSupport.addressFromWord(word)).isEqualTo("0xabc123def4567890123456789012345678901234");
    }

    @Test
    void encodeInt24HandlesNegativeTicks() {
        String encoded = EvmAbiSupport.encodeInt24(-887272);
        assertThat(EvmAbiSupport.int24FromWord(encoded)).isEqualTo(-887272);
    }

    @Test
    void uintFromWordParsesLargeValues() {
        assertThat(EvmAbiSupport.uintFromWord("0000000000000000000000000000000000000000000000000de0b6b3a7640000"))
                .isEqualTo(new BigInteger("1000000000000000000"));
    }
}
