package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Small shared helpers for decoding static calldata arguments from normalized hex input.
 */
public final class CalldataDecodingSupport {

    private static final int SELECTOR_HEX_LENGTH = 8;
    private static final int WORD_HEX_LENGTH = 64;

    private CalldataDecodingSupport() {
    }

    public static String decodeAddressArgument(String inputData, int argumentIndex) {
        String word = decodeWord(inputData, argumentIndex);
        if (word == null) {
            return null;
        }
        return OnChainRawTransactionView.normalizeAddress(word.substring(24));
    }

    public static BigInteger decodeUint256Argument(String inputData, int argumentIndex) {
        String word = decodeWord(inputData, argumentIndex);
        if (word == null) {
            return null;
        }
        try {
            return new BigInteger(word, 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static boolean containsEmbeddedSelector(String inputData, String selector) {
        if (inputData == null || selector == null || !inputData.startsWith("0x")) {
            return false;
        }
        String normalizedSelector = selector.startsWith("0x") ? selector.substring(2).toLowerCase() : selector.toLowerCase();
        if (normalizedSelector.length() != SELECTOR_HEX_LENGTH || inputData.length() <= 10) {
            return false;
        }
        return inputData.substring(10).contains(normalizedSelector);
    }

    public static boolean containsAsciiFragment(String inputData, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        String printableAscii = printableAscii(inputData);
        return printableAscii != null && printableAscii.contains(fragment.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean containsAnyAsciiFragment(String inputData, String... fragments) {
        if (fragments == null || fragments.length == 0) {
            return false;
        }
        String printableAscii = printableAscii(inputData);
        if (printableAscii == null) {
            return false;
        }
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank()
                    && printableAscii.contains(fragment.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String decodeWord(String inputData, int argumentIndex) {
        if (inputData == null || argumentIndex < 0 || !inputData.startsWith("0x")) {
            return null;
        }
        int start = 2 + SELECTOR_HEX_LENGTH + (argumentIndex * WORD_HEX_LENGTH);
        int end = start + WORD_HEX_LENGTH;
        if (inputData.length() < end) {
            return null;
        }
        String word = inputData.substring(start, end);
        return word.matches("[0-9a-fA-F]{64}") ? word : null;
    }

    private static String printableAscii(String inputData) {
        if (inputData == null || !inputData.startsWith("0x") || inputData.length() <= 2) {
            return null;
        }
        String hex = inputData.substring(2);
        if ((hex.length() & 1) == 1) {
            hex = hex.substring(0, hex.length() - 1);
        }
        StringBuilder builder = new StringBuilder(hex.length() / 2);
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            String pair = hex.substring(i, i + 2);
            try {
                int value = Integer.parseInt(pair, 16);
                if (value >= 32 && value <= 126) {
                    builder.append(Character.toLowerCase((char) value));
                } else {
                    builder.append(' ');
                }
            } catch (NumberFormatException ex) {
                builder.append(' ');
            }
        }
        String printable = builder.toString();
        return printable.isBlank() ? null : printable;
    }
}
