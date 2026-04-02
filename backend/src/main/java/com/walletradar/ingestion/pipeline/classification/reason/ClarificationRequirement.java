package com.walletradar.ingestion.pipeline.classification.reason;

/**
 * Structured clarification requirements mapped to persisted reason codes.
 */
public enum ClarificationRequirement {
    EXECUTION_STATUS(ClassificationReasonCode.MISSING_EXECUTION_STATUS),
    EFFECTIVE_GAS_PRICE(ClassificationReasonCode.MISSING_EFFECTIVE_GAS_PRICE),
    GAS_USED(ClassificationReasonCode.MISSING_GAS_USED),
    CONTRACT_ADDRESS(ClassificationReasonCode.MISSING_CONTRACT_ADDRESS),
    BRIDGE_PAIR_EVIDENCE(ClassificationReasonCode.BRIDGE_PAIR_EVIDENCE_REQUIRED),
    GMX_DEPOSIT_REQUEST_CORRELATION(ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED),
    GMX_DEPOSIT_SETTLEMENT_CORRELATION(ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED),
    GMX_WITHDRAWAL_REQUEST_CORRELATION(ClassificationReasonCode.GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED),
    GMX_WITHDRAWAL_SETTLEMENT_CORRELATION(ClassificationReasonCode.GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED),
    GMX_DERIVATIVE_REQUEST_CORRELATION(ClassificationReasonCode.GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED),
    GMX_DERIVATIVE_EXECUTION_EVIDENCE(ClassificationReasonCode.GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED),
    COW_ORDER_SETTLEMENT_CORRELATION(ClassificationReasonCode.COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED);

    private final ClassificationReasonCode reasonCode;

    ClarificationRequirement(ClassificationReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }

    public ClassificationReasonCode reasonCode() {
        return reasonCode;
    }

    public String persistedCode() {
        return reasonCode.code();
    }
}
