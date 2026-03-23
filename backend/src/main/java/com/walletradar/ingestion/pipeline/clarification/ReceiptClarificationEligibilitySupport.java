package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;
import java.util.Set;

/**
 * Allowlist gate for full-receipt clarification on residual review families.
 */
public final class ReceiptClarificationEligibilitySupport {

    private static final Set<String> NON_ECONOMIC_ALLOWLIST = Set.of(
            "0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa",
            "0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630",
            "0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e",
            "0x9867f9d202764ad9d019b0f89cb4b35e96cbc35bd5ac2fabea1edf5c7412bdf2"
    );

    private ReceiptClarificationEligibilitySupport() {
    }

    public static boolean isEligible(NormalizedTransaction normalizedTransaction, OnChainRawTransactionView view) {
        if (normalizedTransaction == null || view == null) {
            return false;
        }
        if (normalizedTransaction.getStatus() != NormalizedTransactionStatus.NEEDS_REVIEW) {
            return false;
        }
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? List.of()
                : normalizedTransaction.getMissingDataReasons();
        if (NON_ECONOMIC_ALLOWLIST.contains(String.valueOf(view.txHash()).toLowerCase())) {
            return true;
        }
        if (view.networkId() != null
                && view.toAddress() != null
                && "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364".equals(view.toAddress())
                && "0xac9650d8".equals(view.methodId())
                && reasons.contains("ROUTER_METHOD_OVERLOAD_UNSUPPORTED")) {
            return true;
        }
        return "0xc16ae7a4".equals(view.methodId()) && reasons.contains("CLASSIFICATION_FAILED");
    }
}
