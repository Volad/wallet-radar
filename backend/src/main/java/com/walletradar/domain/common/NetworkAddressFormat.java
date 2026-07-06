package com.walletradar.domain.common;

import com.walletradar.session.support.TonAddressCanonicalizer;

import java.util.Locale;

/**
 * Per-network address / txHash canonicalisation:
 * <ul>
 *   <li>EVM (default): lower-case hex with {@code 0x} prefix preserved.</li>
 *   <li>Solana: base58 strings are case-sensitive and persisted as emitted by the RPC.</li>
 *   <li>TON: friendly {@code UQ.../EQ...} preserved, raw {@code workchain:hex} lower-cased.</li>
 * </ul>
 *
 * <p>This eliminates accidental lower-casing of Solana base58 signatures and TON friendly
 * addresses, which used to break cross-system (FA-001) continuity matching for Bybit ↔
 * SOL / TON corridors.</p>
 */
public final class NetworkAddressFormat {

    private NetworkAddressFormat() {
    }

    public static boolean isEvm(NetworkId networkId) {
        return networkId != null
                && networkId != NetworkId.SOLANA
                && networkId != NetworkId.TON;
    }

    /**
     * Canonical address for the given network. Returns {@code null} for blank input.
     */
    public static String canonicalAddress(NetworkId networkId, String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (networkId == NetworkId.SOLANA) {
            return trimmed;
        }
        if (networkId == NetworkId.TON || TonAddressCanonicalizer.looksLikeTon(trimmed)) {
            return TonAddressCanonicalizer.preferredMemberRef(trimmed);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        // Network unknown and shape ambiguous: leave case intact so callers can still match
        // case-sensitive identifiers (e.g. base58) and downstream classifiers can decide.
        return trimmed;
    }

    /**
     * Canonical txHash for the given network. EVM hashes are lower-cased; Solana signatures stay
     * case-sensitive base58; TON raw hashes are lower-cased.
     */
    public static String canonicalTxHash(NetworkId networkId, String txHash) {
        if (txHash == null) {
            return null;
        }
        String trimmed = txHash.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (networkId == NetworkId.SOLANA) {
            return trimmed;
        }
        if (networkId == NetworkId.TON) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }

    /**
     * Returns {@code true} when the two hashes refer to the same on-chain transaction under the
     * given network's namespace. Solana comparison is case-sensitive (base58); EVM and TON are
     * case-insensitive.
     */
    public static boolean txHashesEqual(NetworkId networkId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String l = left.trim();
        String r = right.trim();
        if (l.isEmpty() || r.isEmpty()) {
            return false;
        }
        if (networkId == NetworkId.SOLANA) {
            return l.equals(r);
        }
        return l.equalsIgnoreCase(r);
    }
}
