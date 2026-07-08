package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

/**
 * Identifies acquisition types that enter the Net AVCO lane at $0 (Tax lane still uses FMV).
 */
public final class ZeroCostAcquisitionSupport {

    private ZeroCostAcquisitionSupport() {
    }

    public static boolean isZeroCostAcquisition(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case REWARD_CLAIM, LP_FEE_CLAIM -> true;
            default -> false;
        };
    }
}
