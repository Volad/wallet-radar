package com.walletradar.session.support;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical TON address forms for {@link com.walletradar.session.application.AccountingUniverseService}
 * index keys: user-facing {@code UQ…}/{@code EQ…} and raw {@code workchain:hex}.
 */
public final class TonAddressCanonicalizer {

    private static final Pattern RAW_PATTERN = Pattern.compile("^(-?\\d+):([0-9a-fA-F]{64})$");
    private static final Pattern FRIENDLY_PATTERN = Pattern.compile("^[UE]Q[0-9A-Za-z_-]{46}$");

    private TonAddressCanonicalizer() {
    }

    public static boolean looksLikeTon(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String trimmed = address.trim();
        return FRIENDLY_PATTERN.matcher(trimmed).matches() || RAW_PATTERN.matcher(trimmed).matches();
    }

    /**
     * All lookup keys for a TON address (friendly + raw when decodable).
     */
    public static List<String> lookupKeys(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        String trimmed = address.trim();
        if (FRIENDLY_PATTERN.matcher(trimmed).matches()) {
            keys.add(trimmed);
            decodeFriendly(trimmed).ifPresent(raw -> keys.add(raw.toLowerCase(Locale.ROOT)));
            return List.copyOf(keys);
        }
        if (RAW_PATTERN.matcher(trimmed).matches()) {
            String raw = trimmed.toLowerCase(Locale.ROOT);
            keys.add(raw);
            encodeFriendly(raw).ifPresent(keys::add);
            return List.copyOf(keys);
        }
        return List.of(trimmed);
    }

    /**
     * Preferred persisted member ref: friendly {@code UQ…} when encodable, else raw {@code 0:hex}.
     */
    public static String preferredMemberRef(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String trimmed = address.trim();
        if (FRIENDLY_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (RAW_PATTERN.matcher(trimmed).matches()) {
            return encodeFriendly(trimmed.toLowerCase(Locale.ROOT)).orElse(trimmed.toLowerCase(Locale.ROOT));
        }
        return trimmed;
    }

    private static java.util.Optional<String> decodeFriendly(String friendly) {
        try {
            String base64 = friendly.trim()
                    .replace('-', '+')
                    .replace('_', '/');
            while (base64.length() % 4 != 0) {
                base64 += "=";
            }
            byte[] decoded = Base64.getDecoder().decode(base64);
            if (decoded.length != 36) {
                return java.util.Optional.empty();
            }
            byte[] addr = new byte[34];
            System.arraycopy(decoded, 0, addr, 0, 34);
            int expectedCrc = ((decoded[34] & 0xff) << 8) | (decoded[35] & 0xff);
            if (crc16(addr) != expectedCrc) {
                return java.util.Optional.empty();
            }
            int workchain = addr[1] == (byte) 0xff ? -1 : Byte.toUnsignedInt(addr[1]);
            StringBuilder hash = new StringBuilder(64);
            for (int i = 2; i < 34; i++) {
                hash.append(String.format("%02x", addr[i]));
            }
            return java.util.Optional.of(workchain + ":" + hash);
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<String> encodeFriendly(String raw) {
        var matcher = RAW_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        try {
            int workchain = Integer.parseInt(matcher.group(1));
            byte[] hash = hexToBytes(matcher.group(2));
            if (hash.length != 32) {
                return java.util.Optional.empty();
            }
            byte[] addr = new byte[34];
            addr[0] = 0x51; // non-bounceable mainnet
            addr[1] = workchain == -1 ? (byte) 0xff : (byte) workchain;
            System.arraycopy(hash, 0, addr, 2, 32);
            byte[] payload = new byte[36];
            System.arraycopy(addr, 0, payload, 0, 34);
            int crc = crc16(addr);
            payload[34] = (byte) ((crc >> 8) & 0xff);
            payload[35] = (byte) (crc & 0xff);
            String encoded = Base64.getEncoder().encodeToString(payload)
                    .replace('+', '-')
                    .replace('/', '_')
                    .replace("=", "");
            return java.util.Optional.of(encoded);
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int offset = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return out;
    }

    private static int crc16(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc ^= (value & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc & 0xffff;
    }
}
