package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
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

    public static List<String> decodeDynamicBytesArrayElements(String inputData) {
        if (inputData == null || !inputData.startsWith("0x")) {
            return List.of();
        }
        String normalized = inputData.trim().toLowerCase(Locale.ROOT);
        String payload = normalized.substring(2);
        if (payload.length() < SELECTOR_HEX_LENGTH + WORD_HEX_LENGTH) {
            return List.of();
        }

        BigInteger arrayOffset = parseWord(payload, SELECTOR_HEX_LENGTH);
        if (arrayOffset == null || arrayOffset.signum() < 0) {
            return List.of();
        }
        long arrayOffsetBytes = safeLongValue(arrayOffset);
        if (arrayOffsetBytes < 0) {
            return List.of();
        }

        int arrayStart = SELECTOR_HEX_LENGTH + (int) (arrayOffsetBytes * 2L);
        if (!hasWord(payload, arrayStart)) {
            return List.of();
        }

        BigInteger lengthValue = parseWord(payload, arrayStart);
        long elementCountLong = safeLongValue(lengthValue);
        if (elementCountLong < 0 || elementCountLong > Integer.MAX_VALUE) {
            return List.of();
        }
        int elementCount = (int) elementCountLong;

        int offsetsStart = arrayStart + WORD_HEX_LENGTH;
        int arrayDataStart = offsetsStart;
        List<String> elements = new ArrayList<>(elementCount);
        for (int index = 0; index < elementCount; index++) {
            int offsetIndex = offsetsStart + (index * WORD_HEX_LENGTH);
            if (!hasWord(payload, offsetIndex)) {
                return List.of();
            }
            BigInteger elementOffset = parseWord(payload, offsetIndex);
            long elementOffsetBytes = safeLongValue(elementOffset);
            if (elementOffsetBytes < 0) {
                return List.of();
            }
            int elementStart = arrayDataStart + (int) (elementOffsetBytes * 2L);
            if (!hasWord(payload, elementStart)) {
                return List.of();
            }
            BigInteger elementLength = parseWord(payload, elementStart);
            long elementLengthBytes = safeLongValue(elementLength);
            if (elementLengthBytes < 0 || elementLengthBytes > Integer.MAX_VALUE) {
                return List.of();
            }
            int dataStart = elementStart + WORD_HEX_LENGTH;
            int dataEnd = dataStart + (int) (elementLengthBytes * 2L);
            if (dataStart < 0 || dataEnd > payload.length() || dataEnd < dataStart) {
                return List.of();
            }
            elements.add("0x" + payload.substring(dataStart, dataEnd));
        }
        return List.copyOf(elements);
    }

    public static List<String> decodeDynamicBytesArraySelectors(String inputData) {
        List<String> elements = decodeDynamicBytesArrayElements(inputData);
        if (elements.isEmpty()) {
            return List.of();
        }
        List<String> selectors = new ArrayList<>(elements.size());
        for (String element : elements) {
            String selector = normalizeSelector(element);
            if (selector != null) {
                selectors.add(selector);
            }
        }
        return List.copyOf(selectors);
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

    private static BigInteger parseWord(String normalizedHex, int start) {
        if (!hasWord(normalizedHex, start)) {
            return null;
        }
        try {
            return new BigInteger(normalizedHex.substring(start, start + WORD_HEX_LENGTH), 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasWord(String normalizedHex, int start) {
        return normalizedHex != null
                && start >= 0
                && normalizedHex.length() >= start + WORD_HEX_LENGTH;
    }

    private static long safeLongValue(BigInteger value) {
        if (value == null || value.signum() < 0 || value.bitLength() > 31) {
            return -1L;
        }
        return value.longValue();
    }

    private static String normalizeSelector(String inputData) {
        if (inputData == null || !inputData.startsWith("0x") || inputData.length() < 10) {
            return null;
        }
        String selector = inputData.substring(0, 10).toLowerCase(Locale.ROOT);
        return selector.matches("0x[0-9a-f]{8}") ? selector : null;
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
