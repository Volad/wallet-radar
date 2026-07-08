package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;

import java.util.Locale;

/**
 * RC-9 D1 — pure, idempotent, order-stable derivation of the on-chain↔CEX corridor correlation
 * triple. Given only {@code (networkId, canonicalTxHash)} it returns the shared corridor key and
 * the canonical counterpart endpoint for the on-chain leg. It performs no {@code Instant.now()},
 * no DB reads, and no mutation, so a full rebuild and an incremental refresh that present the same
 * raw legs always produce a bit-identical key.
 *
 * <p>The {@code BYBIT-CORRIDOR:} prefix is retained for back-compat with the broad "already paired"
 * recognition regex and with {@code corr-family:} replay queue keys.
 */
public final class CorridorCorrelationKeyFactory {

    /** Shared corridor correlation prefix. Retained for back-compat (do not change). */
    public static final String CORRIDOR_PREFIX = CorrelationContract.BYBIT_CORRIDOR_PREFIX;

    private CorridorCorrelationKeyFactory() {
    }

    /**
     * Deterministic corridor correlation id keyed by network + canonical tx hash. EVM/TON hashes
     * are lower-cased via {@link NetworkAddressFormat#canonicalTxHash}; Solana signatures stay
     * case-sensitive base58. Returns {@code null} when the inputs cannot form a corridor key.
     */
    public static String corridorKey(NetworkId networkId, String txHash) {
        if (networkId == null || txHash == null || txHash.isBlank()) {
            return null;
        }
        String canonicalHash = NetworkAddressFormat.canonicalTxHash(networkId, txHash);
        if (canonicalHash == null || canonicalHash.isBlank()) {
            return null;
        }
        return CORRIDOR_PREFIX + networkId.name() + ":" + canonicalHash;
    }

    /** True when the correlation id is a shared corridor key. */
    public static boolean isCorridorKey(String correlationId) {
        return correlationId != null && correlationId.startsWith(CORRIDOR_PREFIX);
    }

    /**
     * Canonical Bybit sub-account endpoint for the on-chain leg's {@code matchedCounterparty} /
     * {@code counterpartyAddress}. Returns the full {@code BYBIT:<uid>:<SUB>} ref when the Bybit
     * wallet already carries a sub-account suffix; otherwise defaults to {@code :FUND}, the only
     * sub-account that holds external corridor deposits (ADR-006 N17). Returns {@code null} when
     * the input is not a Bybit wallet ref.
     */
    public static String bybitSubAccountEndpoint(String bybitWalletRef) {
        if (bybitWalletRef == null || bybitWalletRef.isBlank()
                || !bybitWalletRef.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String remainder = bybitWalletRef.substring("BYBIT:".length()).trim();
        if (remainder.isBlank()) {
            return null;
        }
        int colon = remainder.indexOf(':');
        if (colon < 0) {
            return "BYBIT:" + remainder + ":FUND";
        }
        String uid = remainder.substring(0, colon).trim();
        String subAccount = remainder.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
        if (uid.isBlank() || subAccount.isBlank()) {
            return null;
        }
        return "BYBIT:" + uid + ":" + subAccount;
    }
}
