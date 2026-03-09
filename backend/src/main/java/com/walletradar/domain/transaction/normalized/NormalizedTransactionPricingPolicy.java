package com.walletradar.domain.transaction.normalized;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

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

    public static boolean isLegPriceRequired(NormalizedTransaction tx, NormalizedTransaction.Flow leg) {
        if (tx == null || leg == null) {
            return false;
        }
        if (!isLegPriceRequired(tx.getType(), leg.getQuantityDelta())) {
            return false;
        }
        return !isSameAssetRefundLeg(tx.getType(), tx.getFlows(), leg);
    }

    public static PricingStatus initialPricingStatus(NormalizedTransactionType type) {
        if (type == NormalizedTransactionType.UNCLASSIFIED) {
            return PricingStatus.NOT_REQUIRED;
        }
        return isTypeNeverPriced(type) ? PricingStatus.NOT_REQUIRED : PricingStatus.PENDING;
    }

    public static PricingStatus resolvedPricingStatus(NormalizedTransactionType type) {
        if (type == NormalizedTransactionType.UNCLASSIFIED) {
            return PricingStatus.NOT_REQUIRED;
        }
        return isTypeNeverPriced(type) ? PricingStatus.NOT_REQUIRED : PricingStatus.RESOLVED;
    }

    public static NormalizedTransactionStatus readyStatus(NormalizedTransactionType type) {
        return isTypeNeverPriced(type)
                ? NormalizedTransactionStatus.PENDING_STAT
                : NormalizedTransactionStatus.PENDING_PRICE;
    }

    private static boolean isSameAssetRefundLeg(
            NormalizedTransactionType type,
            List<NormalizedTransaction.Flow> flows,
            NormalizedTransaction.Flow candidate
    ) {
        if (type != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT || flows == null || candidate.getQuantityDelta() == null) {
            return false;
        }
        if (candidate.getQuantityDelta().signum() <= 0) {
            return false;
        }
        String contract = normalizeContract(candidate.getAssetContract());
        if (contract == null) {
            return false;
        }
        for (NormalizedTransaction.Flow other : flows) {
            if (other == null || other == candidate || other.getQuantityDelta() == null || other.getQuantityDelta().signum() >= 0) {
                continue;
            }
            if (contract.equals(normalizeContract(other.getAssetContract()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeContract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
