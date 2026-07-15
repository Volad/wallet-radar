package com.walletradar.application.normalization.pipeline.classification.reason;

import java.util.Arrays;
import java.util.Optional;

/**
 * Centralized persisted reason codes used by classification and clarification.
 */
public enum ClassificationReasonCode {
    MISSING_EXECUTION_STATUS("MISSING_EXECUTION_STATUS"),
    MISSING_EFFECTIVE_GAS_PRICE("MISSING_EFFECTIVE_GAS_PRICE"),
    MISSING_GAS_USED("MISSING_GAS_USED"),
    MISSING_CONTRACT_ADDRESS("MISSING_CONTRACT_ADDRESS"),
    BRIDGE_PAIR_EVIDENCE_REQUIRED("BRIDGE_PAIR_EVIDENCE_REQUIRED"),
    FAILED_TRANSACTION("FAILED_TRANSACTION"),
    CLASSIFICATION_FAILED("CLASSIFICATION_FAILED"),
    INSUFFICIENT_MOVEMENT_EVIDENCE("INSUFFICIENT_MOVEMENT_EVIDENCE"),
    NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED("NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED"),
    LP_POSITION_CORRELATION_REQUIRED("LP_POSITION_CORRELATION_REQUIRED"),
    ROUTED_AGGREGATOR_OUTBOUND_ONLY("ROUTED_AGGREGATOR_OUTBOUND_ONLY"),
    ROUTER_METHOD_OVERLOAD_UNSUPPORTED("ROUTER_METHOD_OVERLOAD_UNSUPPORTED"),
    GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED("GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED"),
    GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED("GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED"),
    GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED("GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED"),
    GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED("GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED"),
    GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED("GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED"),
    GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED("GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED"),
    COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED("COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED"),
    EULER_BATCH_DECODER_REQUIRED("EULER_BATCH_DECODER_REQUIRED"),
    PENDING_UNBONDING_REQUEST("PENDING_UNBONDING_REQUEST"),
    PROMO_SPAM_PHISHING("PROMO_SPAM_PHISHING"),
    SPOOF_TOKEN_CONFUSABLE_SYMBOL("SPOOF_TOKEN_CONFUSABLE_SYMBOL"),
    /**
     * SF-1(c): An ERC-20 token whose symbol is identical to a network's native asset (e.g. an
     * ERC-20 named "ETH" on Arbitrum) and whose contract is not the canonical wrapped-native
     * contract. These are address-poisoning tokens that, if processed, would create phantom
     * disposals against the real native-asset ledger position.
     */
    SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION("SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION"),
    ZERO_AMOUNT_TOKEN_TRANSFER("ZERO_AMOUNT_TOKEN_TRANSFER"),
    RAW_TRANSACTION_MISSING("RAW_TRANSACTION_MISSING"),
    CLARIFICATION_RECEIPT_UNAVAILABLE("CLARIFICATION_RECEIPT_UNAVAILABLE"),
    CLARIFICATION_FULL_RECEIPT_UNAVAILABLE("CLARIFICATION_FULL_RECEIPT_UNAVAILABLE"),
    CLARIFICATION_INSUFFICIENT_EVIDENCE("CLARIFICATION_INSUFFICIENT_EVIDENCE"),
    CLARIFICATION_ATTEMPTS_EXHAUSTED("CLARIFICATION_ATTEMPTS_EXHAUSTED"),
    LP_FEE_SPLIT_EVIDENCE_REQUIRED("LP_FEE_SPLIT_EVIDENCE_REQUIRED"),
    /**
     * V4 / Pancake Infinity in-range exit: sqrtPriceX96 could not be resolved from the archive
     * RPC at the exit block (node pruned / unavailable). Fee split is skipped; full received
     * amount is treated as principal (fee = 0). Conservative — never fabricates income.
     */
    V4_FEE_SPLIT_UNRESOLVED("V4_FEE_SPLIT_UNRESOLVED"),
    /**
     * V4 / Pancake Infinity: computed principal exceeded received quantity — clamped to received.
     * Indicates tick-math rounding or imprecise sqrtPriceX96 for this block.
     */
    LP_FEE_CLAMPED("LP_FEE_CLAMPED"),
    /**
     * BLOCKER-9 (ADR-057): Euler Finance v2 EVK internal variable-debt tracking token.
     * These tokens are pure internal protocol mechanics (debt-position bookkeeping) and must never
     * enter user inventory. Any transaction that receives a positive inflow from a registered EVK
     * debt-token contract is a flash-loan or collateral-loop rebalance; the inflow is transient and
     * carries no real economic value for the user.
     */
    EULER_EVK_INTERNAL_DEBT_TOKEN("EULER_EVK_INTERNAL_DEBT_TOKEN"),
    /**
     * RC-3: Aave protocol-internal variable debt tracking token.
     * These tokens (e.g. variableDebtAvaUSDT, variableDebtAvaEURC on AVALANCHE) are pure
     * liability instruments emitted by Aave V3 to record a user's outstanding variable-rate
     * borrow. They carry no real economic value for the user and must never enter the AVCO
     * ledger. Any flow touching a registered variable-debt contract is excluded from accounting.
     */
    AAVE_VARIABLE_DEBT_TOKEN("AAVE_VARIABLE_DEBT_TOKEN");

    private final String code;

    ClassificationReasonCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ClassificationReasonCode> fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst();
    }
}
