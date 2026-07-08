package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.normalization.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;
import java.util.Set;

/**
 * Allowlist gate for full-receipt clarification on residual review families.
 */
public final class ReceiptClarificationEligibilitySupport {

    private static final Set<String> NON_ECONOMIC_ALLOWLIST = Set.of(
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775",
            "0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7",
            "0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa",
            "0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19",
            "0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8",
            "0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630",
            "0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e"
    );

    private static final Set<String> HASH_ALLOWLIST_FOR_CLASSIFICATION_FAILED = Set.of(
            "0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a",
            "0x1232a2724f8d2c2e0aa436192b31298ef3351b74bf319c347b9ff569830e7a03",
            "0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499",
            "0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da",
            "0x74abf9296937242aab88b493a37072458f003c50be937ac1670299e3aad6053e",
            "0x83978f62a0f05b662a87210263e923ad568d616f5dd8c420d0485e1e21828a61"
    );

    private static final Set<String> HASH_ALLOWLIST_FOR_INSUFFICIENT_MOVEMENT = Set.of(
            "0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a",
            "0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775"
    );

    private static final String MULTICALL_SELECTOR = "0xac9650d8";
    private static final String GMX_HELPER_SEND_WNT_SELECTOR = "7d39aaf1";
    private static final String GMX_HELPER_SEND_TOKENS_SELECTOR = "e6d66ac8";
    private static final String GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED = ClassificationReasonCode.GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED.code();
    private static final String GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED = ClassificationReasonCode.GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED.code();
    private static final String GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED = ClassificationReasonCode.GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED.code();
    private static final String GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED = ClassificationReasonCode.GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED.code();
    private static final String GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED = ClassificationReasonCode.GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED.code();
    private static final String GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED = ClassificationReasonCode.GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED.code();
    private static final String COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED = ClassificationReasonCode.COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED.code();
    private static final String EULER_BATCH_DECODER_REQUIRED = ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code();
    private static final String NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED =
            ClassificationReasonCode.NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED.code();
    private static final String LP_POSITION_CORRELATION_REQUIRED =
            ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code();
    private static final String GPV2_SETTLEMENT_SELECTOR = "0x13d79a0b";
    private static final String ROUTED_AGGREGATOR_OUTBOUND_ONLY = ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code();

    private ReceiptClarificationEligibilitySupport() {
    }

    public static boolean isEligible(NormalizedTransaction normalizedTransaction, OnChainRawTransactionView view) {
        if (normalizedTransaction == null || view == null) {
            return false;
        }
        if (Boolean.TRUE.equals(normalizedTransaction.getExcludedFromAccounting())) {
            return false;
        }
        List<String> reasons = normalizedTransaction.getMissingDataReasons() == null
                ? List.of()
                : normalizedTransaction.getMissingDataReasons();
        if (view.hasFullReceiptClarificationEvidence()
                && !requiresTransferEvidenceRecovery(normalizedTransaction, reasons, view)) {
            return false;
        }
        boolean bridgeEvidenceCandidate = normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE
                && normalizedTransaction.getType() == NormalizedTransactionType.BRIDGE_OUT;
        boolean gmxDerivativeExecutionCandidate = isGmxDerivativeExecutionCandidate(normalizedTransaction, view);
        boolean gmxPoolExitSettlementCandidate = isGmxPoolExitSettlementCandidate(normalizedTransaction, view);
        boolean cowSettlementCandidate = isCowSettlementCandidate(normalizedTransaction, view);
        boolean gmxPendingClarificationCandidate = isGmxPendingClarificationCandidate(normalizedTransaction, reasons);
        boolean oneInchNativeSettlementCandidate = isOneInchNativeSettlementCandidate(normalizedTransaction, reasons);
        boolean eulerPendingClarificationCandidate = isEulerPendingClarificationCandidate(normalizedTransaction, reasons, view);
        boolean nativeSettlementTransferRecoveryCandidate =
                isNativeSettlementTransferRecoveryCandidate(normalizedTransaction, reasons, view);
        boolean lpPositionCorrelationCandidate =
                isLpPositionCorrelationCandidate(normalizedTransaction, reasons, view);
        boolean multicallMissingTransferCandidate =
                isMulticallWithMissingNativeValueTransferEvidence(normalizedTransaction, view);
        if (normalizedTransaction.getStatus() != NormalizedTransactionStatus.NEEDS_REVIEW
                && !bridgeEvidenceCandidate
                && !gmxDerivativeExecutionCandidate
                && !gmxPoolExitSettlementCandidate
                && !cowSettlementCandidate
                && !gmxPendingClarificationCandidate
                && !oneInchNativeSettlementCandidate
                && !eulerPendingClarificationCandidate
                && !nativeSettlementTransferRecoveryCandidate
                && !lpPositionCorrelationCandidate
                && !multicallMissingTransferCandidate) {
            return false;
        }
        if (bridgeEvidenceCandidate
                && reasons.contains(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED)) {
            return true;
        }
        if (normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                && reasons.contains(GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED)
                && isGmxDerivativeRequest(normalizedTransaction)) {
            return true;
        }
        if (normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                && reasons.contains(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                && isGmxDerivativeExecutionFamily(normalizedTransaction)) {
            return true;
        }
        if (normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                && reasons.contains(COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.DEX_ORDER_SETTLEMENT
                && "CoW Swap".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return true;
        }
        if (gmxDerivativeExecutionCandidate) {
            return true;
        }
        if (gmxPoolExitSettlementCandidate) {
            return true;
        }
        if (cowSettlementCandidate) {
            return true;
        }
        if (oneInchNativeSettlementCandidate) {
            return true;
        }
        if (eulerPendingClarificationCandidate) {
            return true;
        }
        if (nativeSettlementTransferRecoveryCandidate) {
            return true;
        }
        if (lpPositionCorrelationCandidate) {
            return true;
        }
        if (multicallMissingTransferCandidate) {
            return true;
        }
        if (normalizedTransaction.getStatus() == NormalizedTransactionStatus.NEEDS_REVIEW) {
            return true;
        }
        String txHash = String.valueOf(view.txHash()).toLowerCase();
        if (NON_ECONOMIC_ALLOWLIST.contains(txHash)) {
            return true;
        }
        if (view.networkId() != null
                && view.toAddress() != null
                && "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364".equals(view.toAddress())
                && "0xac9650d8".equals(view.methodId())
                && reasons.contains(ClassificationReasonCode.ROUTER_METHOD_OVERLOAD_UNSUPPORTED.code())) {
            return true;
        }
        if (HASH_ALLOWLIST_FOR_INSUFFICIENT_MOVEMENT.contains(txHash)
                && reasons.contains(ClassificationReasonCode.INSUFFICIENT_MOVEMENT_EVIDENCE.code())) {
            return true;
        }
        if (reasons.contains(GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED)
                && "0xac9650d8".equals(view.methodId())
                && view.inputData() != null
                && view.inputData().contains(GMX_HELPER_SEND_WNT_SELECTOR)
                && view.inputData().contains(GMX_HELPER_SEND_TOKENS_SELECTOR)) {
            return true;
        }
        if (reasons.contains(GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED)
                && ("0xc30d8910".equals(view.methodId())
                || "0x5ee8ec8f".equals(view.methodId())
                || String.valueOf(view.functionName()).contains("executedeposit")
                || String.valueOf(view.functionName()).contains("executeglvdeposit"))) {
            return true;
        }
        if (HASH_ALLOWLIST_FOR_CLASSIFICATION_FAILED.contains(txHash)
                && reasons.contains(ClassificationReasonCode.CLASSIFICATION_FAILED.code())) {
            return true;
        }
        return "0xc16ae7a4".equals(view.methodId())
                && reasons.contains(ClassificationReasonCode.CLASSIFICATION_FAILED.code());
    }

    private static boolean isGmxPendingClarificationCandidate(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons
    ) {
        if (normalizedTransaction == null
                || normalizedTransaction.getStatus() != NormalizedTransactionStatus.PENDING_CLARIFICATION
                || reasons == null
                || reasons.isEmpty()) {
            return false;
        }
        if (reasons.contains(GMX_DERIVATIVE_REQUEST_CORRELATION_REQUIRED)
                && isGmxDerivativeRequest(normalizedTransaction)) {
            return true;
        }
        if (reasons.contains(GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED)
                && isGmxDerivativeExecutionFamily(normalizedTransaction)) {
            return true;
        }
        if (reasons.contains(COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.DEX_ORDER_SETTLEMENT
                && "CoW Swap".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return true;
        }
        if (reasons.contains(GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.LP_ENTRY_REQUEST
                && "GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return true;
        }
        if (reasons.contains(GMX_WITHDRAWAL_REQUEST_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.LP_EXIT_REQUEST
                && "GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return true;
        }
        if (reasons.contains(GMX_DEPOSIT_SETTLEMENT_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.LP_ENTRY_SETTLEMENT
                && "GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return true;
        }
        return reasons.contains(GMX_WITHDRAWAL_SETTLEMENT_CORRELATION_REQUIRED)
                && normalizedTransaction.getType() == NormalizedTransactionType.LP_EXIT_SETTLEMENT
                && "GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName());
    }

    private static boolean isOneInchNativeSettlementCandidate(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons
    ) {
        return normalizedTransaction != null
                && normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE
                && normalizedTransaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                && "1inch".equalsIgnoreCase(normalizedTransaction.getProtocolName())
                && reasons.contains(ROUTED_AGGREGATOR_OUTBOUND_ONLY);
    }

    private static boolean isEulerPendingClarificationCandidate(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons,
            OnChainRawTransactionView view
    ) {
        return normalizedTransaction != null
                && normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                && "Euler".equalsIgnoreCase(normalizedTransaction.getProtocolName())
                && reasons.contains(EULER_BATCH_DECODER_REQUIRED)
                && "0xc16ae7a4".equals(view.methodId());
    }

    private static boolean isNativeSettlementTransferRecoveryCandidate(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons,
            OnChainRawTransactionView view
    ) {
        return normalizedTransaction != null
                && view != null
                && normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION
                && isNativeSettlementRecoveryType(normalizedTransaction.getType())
                && reasons.contains(NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED)
                && view.syncMethod() == RawSyncMethod.BLOCKSCOUT
                && !view.hasFullReceiptClarificationEvidence();
    }

    /**
     * ADR-044 D3: native-output families whose missing native settlement is recovered via full-receipt
     * WETH {@code Withdrawal} evidence. Broadened from LP_EXIT/LP_FEE_CLAIM to SWAP/UNWRAP/LP_EXIT_*.
     */
    private static boolean isNativeSettlementRecoveryType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL
                || type == NormalizedTransactionType.LP_FEE_CLAIM
                || type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.UNWRAP;
    }

    private static boolean requiresTransferEvidenceRecovery(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null || view == null || reasons == null || reasons.isEmpty()) {
            return false;
        }
        if (!"0xc16ae7a4".equals(view.methodId())) {
            return false;
        }
        if (!view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty()) {
            return false;
        }
        return reasons.contains(ClassificationReasonCode.CLASSIFICATION_FAILED.code())
                || reasons.contains(EULER_BATCH_DECODER_REQUIRED);
    }

    private static boolean isLpPositionCorrelationCandidate(
            NormalizedTransaction normalizedTransaction,
            List<String> reasons,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null || view == null) {
            return false;
        }
        if (!reasons.contains(LP_POSITION_CORRELATION_REQUIRED)) {
            return false;
        }
        NormalizedTransactionType type = normalizedTransaction.getType();
        boolean positionScopedType = type == NormalizedTransactionType.LP_ENTRY
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
        if (!positionScopedType) {
            return false;
        }
        if (view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        if (normalizedTransaction.getStatus() == NormalizedTransactionStatus.PENDING_CLARIFICATION) {
            return true;
        }
        // LP_ENTRY mint transactions (e.g. Velodrome struct-mint) may have been confirmed by the
        // reclassifier even though the tokenId could not be decoded — leaving them CONFIRMED with
        // LP_POSITION_CORRELATION_REQUIRED still unresolved and CLARIFICATION_ATTEMPTS_EXHAUSTED
        // set after a single failed full-receipt fetch.  If the full receipt has never been
        // successfully fetched (fullReceiptClarificationAttempts == 0) we re-admit the transaction
        // for another clarification attempt: the failure was transient (RPC unavailable at the time
        // of the original attempt) rather than permanent.
        return normalizedTransaction.getStatus() == NormalizedTransactionStatus.CONFIRMED
                && type == NormalizedTransactionType.LP_ENTRY
                && reasons.contains(ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code())
                && (normalizedTransaction.getFullReceiptClarificationAttempts() == null
                || normalizedTransaction.getFullReceiptClarificationAttempts() == 0);
    }

    private static boolean isGmxDerivativeRequest(NormalizedTransaction normalizedTransaction) {
        return normalizedTransaction != null
                && normalizedTransaction.getType() == NormalizedTransactionType.DERIVATIVE_ORDER_REQUEST
                && "GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName());
    }

    private static boolean isGmxDerivativeExecutionFamily(NormalizedTransaction normalizedTransaction) {
        if (normalizedTransaction == null || !"GMX".equalsIgnoreCase(normalizedTransaction.getProtocolName())) {
            return false;
        }
        return normalizedTransaction.getType() == NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION
                || normalizedTransaction.getType() == NormalizedTransactionType.DERIVATIVE_ORDER_CANCEL
                || normalizedTransaction.getType() == NormalizedTransactionType.DERIVATIVE_POSITION_INCREASE
                || normalizedTransaction.getType() == NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE;
    }

    private static boolean isGmxDerivativeExecutionCandidate(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null
                || view == null
                || normalizedTransaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            return false;
        }
        if (normalizedTransaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && normalizedTransaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        if (view.networkId() != NetworkId.ARBITRUM || view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        boolean missingTopLevelTxContext = (view.fromAddress() == null || view.toAddress() == null)
                && (view.functionName() == null || view.functionName().isBlank())
                && (view.inputData() == null
                || view.inputData().isBlank()
                || "deprecated".equalsIgnoreCase(view.inputData()));
        if (!missingTopLevelTxContext) {
            return false;
        }
        return !view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty();
    }

    private static boolean isGmxPoolExitSettlementCandidate(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null
                || view == null
                || normalizedTransaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE) {
            return false;
        }
        if (normalizedTransaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && normalizedTransaction.getType() != NormalizedTransactionType.VAULT_WITHDRAW) {
            return false;
        }
        if (view.networkId() != NetworkId.ARBITRUM || view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        boolean missingTopLevelTxContext = (view.fromAddress() == null || view.toAddress() == null)
                && (view.functionName() == null || view.functionName().isBlank())
                && (view.inputData() == null
                || view.inputData().isBlank()
                || "deprecated".equalsIgnoreCase(view.inputData()));
        boolean explicitWithdrawalSelector = "0xc96fea9f".equals(view.methodId());
        if (!missingTopLevelTxContext && !explicitWithdrawalSelector) {
            return false;
        }
        return !view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty();
    }

    /**
     * Detects multicall transactions (0xac9650d8) that send native ETH but have no token or
     * internal transfer evidence — a symptom of BlockScout indexer lag where the block has
     * been processed in the main tx list but the sub-call logs have not been indexed yet.
     *
     * <p>When this fires, the receipt clarification will fall back to RPC
     * ({@code eth_getTransactionReceipt}) to recover ERC-20 Transfer events from the receipt
     * logs, which are always present even when the explorer has not indexed them.
     */
    private static boolean isMulticallWithMissingNativeValueTransferEvidence(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null || view == null) {
            return false;
        }
        if (normalizedTransaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return false;
        }
        if (view.syncMethod() != RawSyncMethod.BLOCKSCOUT) {
            return false;
        }
        if (view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        if (!MULTICALL_SELECTOR.equals(view.methodId())) {
            return false;
        }
        if (view.rawValue() == null || view.rawValue().signum() <= 0) {
            return false;
        }
        return view.explorerTokenTransfers().isEmpty() && view.explorerInternalTransfers().isEmpty();
    }

    private static boolean isCowSettlementCandidate(
            NormalizedTransaction normalizedTransaction,
            OnChainRawTransactionView view
    ) {
        if (normalizedTransaction == null
                || view == null
                || normalizedTransaction.getStatus() != NormalizedTransactionStatus.PENDING_PRICE
                || normalizedTransaction.getType() != NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return false;
        }
        if (view.networkId() != NetworkId.ARBITRUM || view.hasFullReceiptClarificationEvidence()) {
            return false;
        }
        boolean missingTopLevelTxContext = (view.fromAddress() == null || view.toAddress() == null)
                && (view.functionName() == null || view.functionName().isBlank())
                && (view.inputData() == null
                || view.inputData().isBlank()
                || "deprecated".equalsIgnoreCase(view.inputData()));
        boolean explicitSettlementSelector = GPV2_SETTLEMENT_SELECTOR.equals(view.methodId());
        if (!missingTopLevelTxContext && !explicitSettlementSelector) {
            return false;
        }
        return !view.explorerTokenTransfers().isEmpty() || !view.explorerInternalTransfers().isEmpty();
    }
}
