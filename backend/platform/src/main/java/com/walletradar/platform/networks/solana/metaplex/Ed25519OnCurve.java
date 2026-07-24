package com.walletradar.platform.networks.solana.metaplex;

import java.math.BigInteger;

/**
 * Determines whether a 32-byte value is a valid (on-curve) Ed25519 compressed point.
 *
 * <p>Solana's {@code findProgramAddress} derives a Program-Derived Address by hashing the seeds and
 * a decreasing {@code bump} until the resulting 32-byte SHA-256 digest is <b>off</b> the Ed25519
 * curve (so it can never collide with a key that has a private key). Reproducing that off-curve test
 * is therefore required to derive the canonical Metaplex metadata PDA deterministically.</p>
 *
 * <p>A compressed point encodes {@code y} (little-endian, high bit = sign of {@code x}). It lies on
 * the curve iff {@code y < p} and {@code u/v} is a quadratic residue, where {@code u = y²-1} and
 * {@code v = d·y²+1}. Validated against libsodium's {@code crypto_core_ed25519_is_valid_point}.</p>
 */
final class Ed25519OnCurve {

    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D;
    /** Exponent {@code (p-5)/8} used for the candidate square-root. */
    private static final BigInteger SQRT_EXPONENT = P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8));

    static {
        BigInteger inverse121666 = BigInteger.valueOf(121666).modInverse(P);
        D = BigInteger.valueOf(-121665).mod(P).multiply(inverse121666).mod(P);
    }

    private Ed25519OnCurve() {
    }

    static boolean isOnCurve(byte[] point) {
        if (point == null || point.length != 32) {
            return false;
        }
        byte[] littleEndian = point.clone();
        littleEndian[31] = (byte) (littleEndian[31] & 0x7f);
        BigInteger y = fromLittleEndian(littleEndian);
        if (y.compareTo(P) >= 0) {
            return false;
        }
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.multiply(v).mod(P).multiply(v).mod(P);
        BigInteger v7 = v3.multiply(v3).mod(P).multiply(v).mod(P);
        BigInteger candidate = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(SQRT_EXPONENT, P)).mod(P);
        BigInteger check = v.multiply(candidate).mod(P).multiply(candidate).mod(P);
        return check.equals(u) || check.equals(u.negate().mod(P));
    }

    private static BigInteger fromLittleEndian(byte[] littleEndian) {
        byte[] bigEndian = new byte[littleEndian.length];
        for (int i = 0; i < littleEndian.length; i++) {
            bigEndian[i] = littleEndian[littleEndian.length - 1 - i];
        }
        return new BigInteger(1, bigEndian);
    }
}
