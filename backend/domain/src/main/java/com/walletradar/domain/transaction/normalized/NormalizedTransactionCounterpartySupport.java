package com.walletradar.domain.transaction.normalized;

import java.math.BigDecimal;

/**
 * Counterparty presence checks on canonical normalized rows.
 */
public final class NormalizedTransactionCounterpartySupport {

    private NormalizedTransactionCounterpartySupport() {
    }

    public static boolean flowsMissingCounterparty(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return true;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null) {
                continue;
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (!present(flow.getCounterpartyAddress())) {
                return true;
            }
        }
        return false;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
