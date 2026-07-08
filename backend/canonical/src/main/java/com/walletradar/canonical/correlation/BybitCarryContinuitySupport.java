package com.walletradar.canonical.correlation;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.normalized.VenueInternalCarryKind;

import java.util.Locale;

/**
 * Pure helpers for stamping and resolving Bybit venue-internal carry metadata on canonical rows.
 */
public final class BybitCarryContinuitySupport {

    private BybitCarryContinuitySupport() {
    }

    /**
     * Stamps {@code venue}, {@code bybitUid}, carry routing fields, and {@code selfTransferNoop}
     * when the transaction is a Bybit row with enough context.
     */
    public static void stamp(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getSource() != NormalizedTransactionSource.BYBIT) {
            return;
        }
        transaction.setVenue(CorrelationContract.VENUE_BYBIT);
        String wallet = transaction.getWalletAddress();
        transaction.setBybitUid(extractBybitUid(wallet));

        String correlationId = transaction.getCorrelationId();
        VenueInternalCarryKind carryKind = resolveVenueInternalCarryKind(transaction, correlationId);
        transaction.setVenueInternalCarry(carryKind);
        transaction.setCarrySourceHint(resolveCarrySourceHint(correlationId));

        if (transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            transaction.setSelfTransferNoop(resolveSelfTransferNoop(transaction));
        }
    }

    /**
     * Resolves whether an INTERNAL_TRANSFER is a same-position self-transfer no-op for replay.
     * Returns {@code null} when the row is not a Bybit internal transfer.
     */
    public static Boolean resolveSelfTransferNoop(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.BYBIT
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return null;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith(CorrelationContract.BYBIT_CROSS_UID_V1_PREFIX)) {
            return false;
        }
        if (correlationId != null
                && correlationId.startsWith(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX)) {
            String wallet = transaction.getWalletAddress();
            String counterparty = resolveCounterparty(transaction);
            if (walletEndsWith(wallet, CorrelationContract.WALLET_SUFFIX_FUND)
                    || walletEndsWith(counterparty, CorrelationContract.WALLET_SUFFIX_FUND)) {
                return false;
            }
        }
        String normalizedWallet = positionWalletAddress(transaction);
        String counterparty = resolveCounterparty(transaction);
        if (counterparty == null || counterparty.isBlank()) {
            return false;
        }
        String normalizedCounterparty = positionWalletAddress(counterparty);
        return normalizedWallet != null && normalizedWallet.equals(normalizedCounterparty);
    }

    public static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return null;
        }
        String upper = walletAddress.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("BYBIT:")) {
            return null;
        }
        String withoutPrefix = walletAddress.substring("BYBIT:".length());
        int colon = withoutPrefix.indexOf(':');
        return colon > 0 ? withoutPrefix.substring(0, colon) : withoutPrefix;
    }

    private static VenueInternalCarryKind resolveVenueInternalCarryKind(
            NormalizedTransaction transaction,
            String correlationId
    ) {
        if (correlationId != null) {
            if (usesCorrFamilyPrefix(correlationId)) {
                return VenueInternalCarryKind.CORR_FAMILY;
            }
            if (correlationId.startsWith(CorrelationContract.BYBIT_ECON_V1_PREFIX)
                    && !Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
                return VenueInternalCarryKind.EARN_CARRY_FIFO;
            }
        }
        if (transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER) {
            Boolean noop = resolveSelfTransferNoop(transaction);
            if (Boolean.TRUE.equals(noop)) {
                return VenueInternalCarryKind.SELF_TRANSFER_NOOP;
            }
        }
        return VenueInternalCarryKind.NOT_APPLICABLE;
    }

    private static boolean usesCorrFamilyPrefix(String correlationId) {
        return correlationId.startsWith(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_CROSS_UID_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_REKEYED_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_IT_BUNDLE_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_EARN_ONCHAIN_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX)
                || correlationId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX);
    }

    private static String resolveCarrySourceHint(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_COLLAPSED_V1_PREFIX)) {
            return "collapsed";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_CROSS_UID_V1_PREFIX)) {
            return "cross-uid";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_REKEYED_V1_PREFIX)) {
            return "rekeyed";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_IT_BUNDLE_V1_PREFIX)) {
            return "it-bundle";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)) {
            return "earn-principal";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_EARN_ONCHAIN_V1_PREFIX)) {
            return "earn-onchain";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX)) {
            return "earn-onchain-fund";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_ECON_V1_PREFIX)) {
            return "econ";
        }
        if (correlationId.startsWith(CorrelationContract.BYBIT_CORRIDOR_PREFIX)) {
            return "corridor";
        }
        return null;
    }

    private static String resolveCounterparty(NormalizedTransaction transaction) {
        String counterparty = transaction.getMatchedCounterparty();
        if (counterparty == null || counterparty.isBlank()) {
            counterparty = transaction.getCounterpartyAddress();
        }
        return counterparty;
    }

    private static boolean walletEndsWith(String wallet, String suffix) {
        return wallet != null && wallet.toUpperCase(Locale.ROOT).endsWith(suffix);
    }

    /**
     * UID-level position key for Bybit UTA/FUND sub-accounts (mirrors accounting identity contract).
     */
    private static String positionWalletAddress(NormalizedTransaction transaction) {
        if (transaction == null) {
            return null;
        }
        return positionWalletAddress(transaction.getWalletAddress());
    }

    private static String positionWalletAddress(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return walletAddress;
        }
        if (walletAddress.endsWith(":UTA") || walletAddress.endsWith(":FUND")) {
            return walletAddress.substring(0, walletAddress.lastIndexOf(':'));
        }
        return walletAddress;
    }
}
