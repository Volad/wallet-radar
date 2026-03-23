package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Guards pricing/replay from fee-only economic rows that still lack persisted movement evidence.
 */
public final class PricingReadinessSupport {

    private static final Set<NormalizedTransactionType> NON_ECONOMIC_TYPES = EnumSet.of(
            NormalizedTransactionType.APPROVE,
            NormalizedTransactionType.ADMIN_CONFIG,
            NormalizedTransactionType.INTERNAL_TRANSFER,
            NormalizedTransactionType.LP_POSITION_STAKE,
            NormalizedTransactionType.LP_POSITION_UNSTAKE,
            NormalizedTransactionType.WRAP,
            NormalizedTransactionType.UNWRAP
    );

    private PricingReadinessSupport() {
    }

    public static boolean requiresMovementEvidence(
            NormalizedTransactionType type,
            NormalizedTransactionStatus status
    ) {
        if (type == null || status == null) {
            return false;
        }
        if (status != NormalizedTransactionStatus.PENDING_PRICE
                && status != NormalizedTransactionStatus.CONFIRMED) {
            return false;
        }
        return !NON_ECONOMIC_TYPES.contains(type) && type != NormalizedTransactionType.UNKNOWN;
    }

    public static boolean hasNonFeeMovement(List<NormalizedTransaction.Flow> flows) {
        if (flows == null || flows.isEmpty()) {
            return false;
        }
        return flows.stream()
                .anyMatch(flow -> flow != null
                        && flow.getRole() != NormalizedLegRole.FEE
                        && flow.getQuantityDelta() != null
                        && flow.getQuantityDelta().signum() != 0);
    }
}
