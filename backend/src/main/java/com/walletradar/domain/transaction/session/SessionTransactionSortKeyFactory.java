package com.walletradar.domain.transaction.session;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.Comparator;
import java.util.Locale;

/**
 * Builds deterministic sort keys for session replay and timeline reads.
 */
public final class SessionTransactionSortKeyFactory {

    private SessionTransactionSortKeyFactory() {
    }

    public static String fromNormalized(NormalizedTransaction tx, SessionTransactionSourceType sourceType) {
        long timestampMillis = tx.getBlockTimestamp() != null ? tx.getBlockTimestamp().toEpochMilli() : 0L;
        int minLogIndex = tx.getFlows() == null
                ? 0
                : tx.getFlows().stream()
                .map(NormalizedTransaction.Flow::getLogIndex)
                .filter(i -> i != null && i >= 0)
                .min(Comparator.naturalOrder())
                .orElse(0);
        int sourceOrder = sourceOrder(sourceType);
        int networkOrder = networkOrder(tx.getNetworkId());

        return "%019d|%010d|%02d|%03d|%s|%s|%s".formatted(
                timestampMillis,
                minLogIndex,
                sourceOrder,
                networkOrder,
                safeLower(tx.getTxHash()),
                safeLower(tx.getWalletAddress()),
                safeLower(tx.getId()));
    }

    private static int sourceOrder(SessionTransactionSourceType sourceType) {
        if (sourceType == null) {
            return 99;
        }
        return switch (sourceType) {
            case CHAIN -> 0;
            case MANUAL -> 1;
            case OVERRIDE -> 2;
        };
    }

    private static int networkOrder(NetworkId networkId) {
        return networkId != null ? networkId.ordinal() : 999;
    }

    private static String safeLower(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
