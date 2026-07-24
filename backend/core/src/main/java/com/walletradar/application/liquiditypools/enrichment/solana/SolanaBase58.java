package com.walletradar.application.liquiditypools.enrichment.solana;

import java.math.BigInteger;

/**
 * Minimal Base58 (Bitcoin/Solana alphabet) encoder for 32-byte account public keys decoded from raw
 * account data (e.g. the {@code lbPair} pointer inside a Meteora DLMM position account). Solana
 * pubkeys are 32 raw bytes on the wire; the human/base58 form is required to query the REST APIs.
 */
public final class SolanaBase58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private SolanaBase58() {
    }

    /** Encodes raw bytes to a Base58 string, preserving leading-zero bytes as leading '1' chars. */
    public static String encode(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }
        BigInteger value = new BigInteger(1, input);
        StringBuilder builder = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] divmod = value.divideAndRemainder(BASE);
            value = divmod[0];
            builder.append(ALPHABET.charAt(divmod[1].intValue()));
        }
        for (int i = 0; i < leadingZeros; i++) {
            builder.append('1');
        }
        return builder.reverse().toString();
    }
}
