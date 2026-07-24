package com.walletradar.platform.networks.solana.metaplex;

import com.walletradar.platform.networks.solana.metaplex.MetaplexMetadataClient.MetaplexTokenMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Borsh decoder for the fixed prefix of a Metaplex {@code Metadata} account.
 *
 * <p>Account layout (only the prefix we need):</p>
 * <pre>
 *   offset  0 : key                (u8)
 *   offset  1 : update_authority   (Pubkey, 32 bytes)
 *   offset 33 : mint               (Pubkey, 32 bytes)
 *   offset 65 : data.name          (Borsh String: u32 LE length + puffed bytes)
 *             : data.symbol        (Borsh String: u32 LE length + puffed bytes)
 * </pre>
 *
 * <p>Metaplex "puffs" the strings to their maximum length with trailing NUL padding, so the decoded
 * bytes are trimmed of trailing NUL / whitespace. Never throws — malformed or truncated data yields
 * {@link Optional#empty()}.</p>
 */
final class MetaplexMetadataDecoder {

    private static final int DATA_OFFSET = 1 + 32 + 32;
    private static final int LENGTH_PREFIX_BYTES = 4;
    /** Upper bound on a Metaplex string length prefix; guards against a corrupt/huge length. */
    private static final int MAX_STRING_BYTES = 4096;

    private MetaplexMetadataDecoder() {
    }

    static Optional<MetaplexTokenMetadata> decode(byte[] data) {
        if (data == null || data.length <= DATA_OFFSET) {
            return Optional.empty();
        }
        Cursor cursor = new Cursor(DATA_OFFSET);
        String name = readString(data, cursor);
        String symbol = readString(data, cursor);
        if ((name == null || name.isBlank()) && (symbol == null || symbol.isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new MetaplexTokenMetadata(blankToNull(name), blankToNull(symbol)));
    }

    private static String readString(byte[] data, Cursor cursor) {
        int lengthOffset = cursor.position;
        if (lengthOffset + LENGTH_PREFIX_BYTES > data.length) {
            cursor.position = data.length;
            return null;
        }
        long length = readUInt32LittleEndian(data, lengthOffset);
        int start = lengthOffset + LENGTH_PREFIX_BYTES;
        if (length < 0 || length > MAX_STRING_BYTES || start + length > data.length) {
            cursor.position = data.length;
            return null;
        }
        int len = (int) length;
        String value = new String(data, start, len, StandardCharsets.UTF_8);
        cursor.position = start + len;
        return trimPadding(value);
    }

    private static long readUInt32LittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static String trimPadding(String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0) {
            char ch = value.charAt(end - 1);
            if (ch == '\u0000' || Character.isWhitespace(ch)) {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Mutable read position for sequential Borsh field decoding. */
    private static final class Cursor {
        private int position;

        private Cursor(int position) {
            this.position = position;
        }
    }
}
