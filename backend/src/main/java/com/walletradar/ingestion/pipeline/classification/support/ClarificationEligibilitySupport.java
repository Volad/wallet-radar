package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

/**
 * Decides whether a known on-chain transaction is genuinely receipt-clarifiable.
 */
public final class ClarificationEligibilitySupport {

    private ClarificationEligibilitySupport() {
    }

    public static boolean requiresClarification(
            OnChainRawTransactionView view,
            NormalizedTransactionType type
    ) {
        if (view == null || type == null || type == NormalizedTransactionType.UNKNOWN) {
            return false;
        }
        if (!view.hasExecutionStatusEvidence()) {
            return true;
        }
        if (view.isFeePayer() && (!view.hasGasUsed() || !view.hasGasPriceEvidence())) {
            return true;
        }
        return view.isContractCreation() && !view.hasContractAddress();
    }
}
