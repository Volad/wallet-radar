package com.walletradar.platform.networks.solana;

import java.math.BigInteger;

/**
 * Minimal Base58 (Bitcoin/Solana alphabet) codec for 32-byte account public keys.
 *
 * <p>Solana pubkeys are 32 raw bytes on the wire; the human/base58 form is required to query the
 * RPC APIs and vice-versa. This platform-local copy exists so the RPC transport layer never depends
 * on the core module. Encoding preserves leading-zero bytes as leading {@code '1'} characters and
 * decoding is the exact inverse.</p>
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

    /**
     * Decodes a Base58 string to raw bytes, restoring leading '1' chars as leading-zero bytes.
     *
     * @throws IllegalArgumentException if the string contains a non-alphabet character
     */
    public static byte[] decode(String input) {
        if (input == null || input.isEmpty()) {
            return new byte[0];
        }
        int leadingOnes = 0;
        while (leadingOnes < input.length() && input.charAt(leadingOnes) == '1') {
            leadingOnes++;
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character: " + input.charAt(i));
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : stripSignByte(value.toByteArray());
        byte[] out = new byte[leadingOnes + magnitude.length];
        System.arraycopy(magnitude, 0, out, leadingOnes, magnitude.length);
        return out;
    }

    /** Removes the single leading zero byte {@link BigInteger#toByteArray()} adds for sign, if any. */
    private static byte[] stripSignByte(byte[] bigEndian) {
        if (bigEndian.length > 1 && bigEndian[0] == 0) {
            byte[] trimmed = new byte[bigEndian.length - 1];
            System.arraycopy(bigEndian, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bigEndian;
    }
}
