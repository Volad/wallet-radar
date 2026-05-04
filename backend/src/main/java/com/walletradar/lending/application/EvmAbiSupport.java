package com.walletradar.lending.application;

import org.bouncycastle.jcajce.provider.digest.Keccak;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class EvmAbiSupport {

    private static final HexFormat HEX = HexFormat.of();

    private EvmAbiSupport() {
    }

    static String selector(String signature) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        byte[] hash = digest.digest(signature.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(hash).substring(0, 8);
    }

    static String encodeAddress(String address) {
        String cleaned = cleanHex(address);
        return "0".repeat(64 - cleaned.length()) + cleaned;
    }

    static String addressFromWord(String word) {
        String cleaned = cleanHex(word);
        if (cleaned.length() < 40) {
            return null;
        }
        return "0x" + cleaned.substring(cleaned.length() - 40).toLowerCase();
    }

    static BigInteger uintFromWord(String word) {
        String cleaned = cleanHex(word);
        return cleaned.isBlank() ? BigInteger.ZERO : new BigInteger(cleaned, 16);
    }

    static String wordAt(String data, int index) {
        String cleaned = cleanHex(data);
        int start = index * 64;
        int end = start + 64;
        if (cleaned.length() < end) {
            return null;
        }
        return cleaned.substring(start, end);
    }

    static String cleanHex(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().toLowerCase();
        return cleaned.startsWith("0x") ? cleaned.substring(2) : cleaned;
    }
}
