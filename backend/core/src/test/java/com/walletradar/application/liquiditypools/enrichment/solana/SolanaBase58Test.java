package com.walletradar.application.liquiditypools.enrichment.solana;

import com.walletradar.platform.networks.solana.SolanaBase58;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaBase58Test {

    @Test
    void encodesLeadingZeroBytesAsOnes() {
        assertThat(SolanaBase58.encode(new byte[]{0})).isEqualTo("1");
        assertThat(SolanaBase58.encode(new byte[]{0, 0})).isEqualTo("11");
    }

    @Test
    void encodesSmallValues() {
        assertThat(SolanaBase58.encode(new byte[]{1})).isEqualTo("2");
        assertThat(SolanaBase58.encode(new byte[]{58})).isEqualTo("21");
    }

    @Test
    void encodesRealPubkey() {
        // Raw 32 bytes of the Meteora SOL-USDC DLMM pool (evidence anchor), decoded from base58.
        byte[] raw = fromHex("013df47652b6dd4eb238be8ab232f0ee940508cb2e3540669cff004fa6711043");
        assertThat(SolanaBase58.encode(raw)).isEqualTo("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6");
    }

    @Test
    void emptyInputEncodesToEmpty() {
        assertThat(SolanaBase58.encode(new byte[0])).isEmpty();
        assertThat(SolanaBase58.encode(null)).isEmpty();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
