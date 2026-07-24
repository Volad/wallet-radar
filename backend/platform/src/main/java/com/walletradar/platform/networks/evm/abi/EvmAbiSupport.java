package com.walletradar.platform.networks.evm.abi;

import org.bouncycastle.jcajce.provider.digest.Keccak;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class EvmAbiSupport {

    private static final HexFormat HEX = HexFormat.of();

    private EvmAbiSupport() {
    }

    public static String selector(String signature) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        byte[] hash = digest.digest(signature.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(hash).substring(0, 8);
    }

    /**
     * Computes {@code keccak256} over the raw bytes represented by the given hex string
     * (with or without {@code 0x} prefix) and returns the 32-byte digest as lowercase hex
     * (no {@code 0x} prefix). Used to derive Solidity mapping storage slots for {@code extsload}
     * reads (e.g. Uniswap V4 / Pancake Infinity {@code pools[poolId]} state).
     */
    public static String keccak256Hex(String hexInput) {
        byte[] input = HEX.parseHex(cleanHex(hexInput));
        Keccak.Digest256 digest = new Keccak.Digest256();
        return HEX.formatHex(digest.digest(input));
    }

    public static String encodeAddress(String address) {
        String cleaned = cleanHex(address);
        return "0".repeat(64 - cleaned.length()) + cleaned;
    }

    public static String encodeUint256(BigInteger value) {
        if (value == null || value.signum() < 0) {
            value = BigInteger.ZERO;
        }
        String hex = value.toString(16);
        return "0".repeat(64 - hex.length()) + hex;
    }

    public static String encodeInt24(int tick) {
        BigInteger value = BigInteger.valueOf(tick);
        if (tick < 0) {
            value = BigInteger.valueOf(2).pow(256).add(value);
        }
        return encodeUint256(value);
    }

    public static String encodeInt16(int wordPos) {
        BigInteger value = BigInteger.valueOf(wordPos);
        if (wordPos < 0) {
            value = BigInteger.ONE.shiftLeft(256).add(value);
        }
        return encodeUint256(value);
    }

    public static BigInteger signedInt128FromWord(String word) {
        if (word == null) return BigInteger.ZERO;
        BigInteger raw = uintFromWord(word);
        if (raw.testBit(255)) {
            return raw.subtract(BigInteger.ONE.shiftLeft(256));
        }
        return raw;
    }

    public static String addressFromWord(String word) {
        String cleaned = cleanHex(word);
        if (cleaned.length() < 40) {
            return null;
        }
        return "0x" + cleaned.substring(cleaned.length() - 40).toLowerCase();
    }

    public static BigInteger uintFromWord(String word) {
        String cleaned = cleanHex(word);
        return cleaned.isBlank() ? BigInteger.ZERO : new BigInteger(cleaned, 16);
    }

    public static int int24FromWord(String word) {
        BigInteger raw = uintFromWord(word);
        if (raw.testBit(255)) {
            raw = raw.subtract(BigInteger.ONE.shiftLeft(256));
        }
        return raw.intValue();
    }

    public static String wordAt(String data, int index) {
        String cleaned = cleanHex(data);
        int start = index * 64;
        int end = start + 64;
        if (cleaned.length() < end) {
            return null;
        }
        return cleaned.substring(start, end);
    }

    public static String cleanHex(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().toLowerCase();
        return cleaned.startsWith("0x") ? cleaned.substring(2) : cleaned;
    }
}
