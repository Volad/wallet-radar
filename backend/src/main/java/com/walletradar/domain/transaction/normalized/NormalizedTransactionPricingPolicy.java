package com.walletradar.domain.transaction.normalized;

import java.math.BigDecimal;

/**
 * Shared pricing policy for normalized transaction pipeline stages.
 */
public final class NormalizedTransactionPricingPolicy {

    private NormalizedTransactionPricingPolicy() {
    }

    public static boolean isTypeNeverPriced(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return type == NormalizedTransactionType.APPROVAL
                || type == NormalizedTransactionType.LEND_DEPOSIT
                || type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE
                || type == NormalizedTransactionType.WRAP
                || type == NormalizedTransactionType.UNWRAP;
    }

    public static boolean isLegPriceRequired(NormalizedTransactionType type, BigDecimal quantityDelta) {
        if (quantityDelta == null || isTypeNeverPriced(type)) {
            return false;
        }
        if (type == NormalizedTransactionType.SWAP) {
            return true;
        }
        return quantityDelta.signum() > 0;
    }

    public static PricingStatus initialPricingStatus(NormalizedTransactionType type) {
        return isTypeNeverPriced(type) ? PricingStatus.NOT_REQUIRED : PricingStatus.PENDING;
    }

    public static PricingStatus resolvedPricingStatus(NormalizedTransactionType type) {
        return isTypeNeverPriced(type) ? PricingStatus.NOT_REQUIRED : PricingStatus.RESOLVED;
    }

    public static NormalizedTransactionStatus readyStatus(NormalizedTransactionType type) {
        return isTypeNeverPriced(type)
                ? NormalizedTransactionStatus.PENDING_STAT
                : NormalizedTransactionStatus.PENDING_PRICE;
    }
}
