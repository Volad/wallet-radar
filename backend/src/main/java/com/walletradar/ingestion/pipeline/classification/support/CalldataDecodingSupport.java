package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigInteger;

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
}
