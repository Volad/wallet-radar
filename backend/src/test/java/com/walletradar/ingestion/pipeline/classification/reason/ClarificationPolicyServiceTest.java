package com.walletradar.ingestion.pipeline.classification.reason;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationPolicyServiceTest {

    @Test
    void mergesRequiredClassifierReasonsFromView() {
        ClarificationPolicyService service = new ClarificationPolicyService();
        RawTransaction rawTransaction = rawWithoutReceiptEvidence();

        assertThat(service.mergeClassifierReasons(
                OnChainRawTransactionView.wrap(rawTransaction),
                NormalizedTransactionType.SWAP,
                null
        )).containsExactly(
                ClassificationReasonCode.MISSING_EXECUTION_STATUS.code(),
                ClassificationReasonCode.MISSING_EFFECTIVE_GAS_PRICE.code(),
                ClassificationReasonCode.MISSING_GAS_USED.code()
        );
    }

    @Test
    void failureDecisionBecomesNeedsReviewWhenAttemptsExhausted() {
        ClarificationPolicyService service = new ClarificationPolicyService();
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction()
                .setType(NormalizedTransactionType.SWAP)
                .setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);

        ClarificationDecision pending = service.nextFailureDecision(
                normalizedTransaction,
                rawWithoutReceiptEvidence(),
                ClassificationReasonCode.CLARIFICATION_RECEIPT_UNAVAILABLE.code(),
                1,
                3
        );
        assertThat(pending.status()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(pending.missingDataReasons()).contains(
                ClassificationReasonCode.CLARIFICATION_RECEIPT_UNAVAILABLE.code(),
                ClassificationReasonCode.MISSING_EXECUTION_STATUS.code()
        );

        ClarificationDecision exhausted = service.nextFailureDecision(
                normalizedTransaction,
                rawWithoutReceiptEvidence(),
                ClassificationReasonCode.CLARIFICATION_INSUFFICIENT_EVIDENCE.code(),
                3,
                3
        );
        assertThat(exhausted.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(exhausted.missingDataReasons()).contains(
                ClassificationReasonCode.CLARIFICATION_INSUFFICIENT_EVIDENCE.code(),
                ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code()
        );
    }

    @Test
    void receiptFailureDecisionPreservesStatusAndAppendsReason() {
        ClarificationPolicyService service = new ClarificationPolicyService();
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction()
                .setType(NormalizedTransactionType.UNKNOWN)
                .setStatus(NormalizedTransactionStatus.NEEDS_REVIEW)
                .setMissingDataReasons(List.of(ClassificationReasonCode.CLASSIFICATION_FAILED.code()));

        ClarificationDecision decision = service.nextReceiptFailureDecision(
                normalizedTransaction,
                ClassificationReasonCode.CLARIFICATION_FULL_RECEIPT_UNAVAILABLE.code()
        );

        assertThat(decision.status()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(decision.missingDataReasons()).containsExactly(
                ClassificationReasonCode.CLASSIFICATION_FAILED.code(),
                ClassificationReasonCode.CLARIFICATION_FULL_RECEIPT_UNAVAILABLE.code()
        );
    }

    @Test
    void receiptEligibilityUsesCentralizedAllowlistContract() {
        ClarificationPolicyService service = new ClarificationPolicyService();
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction()
                .setType(NormalizedTransactionType.UNKNOWN)
                .setStatus(NormalizedTransactionStatus.NEEDS_REVIEW)
                .setMissingDataReasons(List.of(ClassificationReasonCode.CLASSIFICATION_FAILED.code()));

        assertThat(service.isReceiptClarificationEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(reviewTailRaw())
        )).isTrue();
    }

    @Test
    void receiptEligibilityKeepsEulerBatchRowsRetryableWhenOnlyReceiptLogsPersisted() {
        ClarificationPolicyService service = new ClarificationPolicyService();
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction()
                .setType(NormalizedTransactionType.UNKNOWN)
                .setStatus(NormalizedTransactionStatus.NEEDS_REVIEW)
                .setProtocolName("Euler")
                .setMissingDataReasons(List.of(ClassificationReasonCode.CLASSIFICATION_FAILED.code()));

        RawTransaction rawTransaction = new RawTransaction()
                .setTxHash("0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df")
                .setNetworkId("AVALANCHE")
                .setWalletAddress("0x1111111111111111111111111111111111111111")
                .setRawData(new Document("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                        .append("methodId", "0xc16ae7a4")
                        .append("input", "0xc16ae7a4000000000000000000000000"))
                .setClarificationEvidence(new Document("receipt", new Document("logs", List.of(
                        new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of("0xborrow"))
                ))));

        assertThat(service.isReceiptClarificationEligible(
                normalizedTransaction,
                OnChainRawTransactionView.wrap(rawTransaction)
        )).isTrue();
    }

    private RawTransaction rawWithoutReceiptEvidence() {
        return new RawTransaction()
                .setTxHash("0xaaa")
                .setNetworkId("ARBITRUM")
                .setWalletAddress("0x1111111111111111111111111111111111111111")
                .setRawData(new Document("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0x2222222222222222222222222222222222222222")
                        .append("methodId", "0x7ff36ab5")
                        .append("input", "0x7ff36ab5000000000000000000000000"));
    }

    private RawTransaction reviewTailRaw() {
        return new RawTransaction()
                .setTxHash("0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a")
                .setNetworkId("BASE")
                .setWalletAddress("0x1111111111111111111111111111111111111111")
                .setRawData(new Document("from", "0x1111111111111111111111111111111111111111")
                        .append("to", "0x2222222222222222222222222222222222222222")
                        .append("methodId", "0xac9650d8")
                        .append("input", "0xac9650d8000000000000000000000000"));
    }
}
