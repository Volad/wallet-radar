package com.walletradar.application.normalization.pipeline.classification.support;

import java.util.List;

/**
 * Detects atomic same-wallet swap shape on a single normalized transaction document.
 *
 * <p>For one {@code NormalizedTransaction}, {@code walletAddress} and {@code networkId} are
 * fixed, so a non-fee inbound movement leg in the same tx is a same-wallet same-network swap,
 * not a cross-chain bridge start.</p>
 */
public final class SameWalletSwapShapeSupport {

    private SameWalletSwapShapeSupport() {
    }

    public static boolean hasSameWalletInboundTransfer(List<RawLeg> movementLegs) {
        if (movementLegs == null || movementLegs.isEmpty()) {
            return false;
        }
        return movementLegs.stream()
                .anyMatch(leg -> !leg.fee() && leg.quantityDelta().signum() > 0);
    }
}
