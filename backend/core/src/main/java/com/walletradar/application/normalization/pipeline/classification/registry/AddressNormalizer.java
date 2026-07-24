package com.walletradar.application.normalization.pipeline.classification.registry;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.Set;

/**
 * Family-aware address normalization for protocol-registry keying (ADR-066).
 *
 * <p>EVM contracts are keyed on the lowercase {@code 0x}-prefixed 20-byte address.
 * Solana program IDs are case-sensitive base58 strings and must be preserved verbatim.
 * Selecting the normalizer by the entry's declared {@code networks} (at load time) and by
 * the query {@link NetworkId} (at lookup time) lets a single registry hold both families
 * without the EVM {@code 0x}/42-char coercion destroying base58 program IDs.</p>
 */
public final class AddressNormalizer {

    /** Solana base58 alphabet, 32–44 characters (program IDs and mints). */
    private static final java.util.regex.Pattern SOLANA_BASE58 =
            java.util.regex.Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");

    private AddressNormalizer() {
    }

    /**
     * Normalizes an address for a single query network. EVM networks use the legacy
     * {@code 0x}-lowercase form; Solana preserves the case-sensitive base58 program ID.
     */
    public static String normalize(NetworkId networkId, String address) {
        if (networkId == NetworkId.SOLANA) {
            return normalizeSolana(address);
        }
        return OnChainRawTransactionView.normalizeAddress(address);
    }

    /**
     * Normalizes a registry entry address by its declared networks. Rejects entries that
     * mix Solana with EVM networks so the normalizer choice is unambiguous (ADR-066).
     *
     * @throws IllegalStateException when the entry declares both Solana and EVM networks
     */
    public static String normalizeForEntry(Set<NetworkId> networks, String address) {
        boolean hasSolana = networks != null && networks.contains(NetworkId.SOLANA);
        boolean hasEvm = networks != null && networks.stream()
                .anyMatch(networkId -> networkId != NetworkId.SOLANA && networkId != NetworkId.TON);
        if (hasSolana && hasEvm) {
            throw new IllegalStateException(
                    "Protocol registry entry must not mix Solana and EVM networks: " + address);
        }
        if (hasSolana) {
            return normalizeSolana(address);
        }
        return OnChainRawTransactionView.normalizeAddress(address);
    }

    /**
     * Case-sensitive base58 normalization for Solana program IDs / mints. Trims surrounding
     * whitespace but never lowercases or {@code 0x}-prefixes. Returns {@code null} when the
     * value is not a plausible base58 identifier.
     */
    public static String normalizeSolana(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty() || !SOLANA_BASE58.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
