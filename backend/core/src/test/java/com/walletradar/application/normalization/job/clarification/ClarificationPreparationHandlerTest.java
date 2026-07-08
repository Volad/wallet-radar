package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.application.linking.pipeline.clarification.RawTransactionClarificationEnricher;
import com.walletradar.application.linking.pipeline.clarification.ReceiptClarificationGateway;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the full-receipt clarification retry-count defect: both
 * {@code fetchFullReceiptForClarificationOrMarkFailure} and {@code fetchFullReceiptForReviewOrMarkFailure}
 * previously hard-coded {@code 1} instead of forwarding the caller's own {@code maxAttempts}, which silently
 * exhausted retries after a single transient failure regardless of configuration.
 */
@ExtendWith(MockitoExtension.class)
class ClarificationPreparationHandlerTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    private ReceiptClarificationGateway clarificationGateway;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private ClarificationPreparationHandler handler;

    @BeforeEach
    void setUp() {
        RawTransactionClarificationEnricher enricher = new RawTransactionClarificationEnricher();
        ClarificationPolicyService clarificationPolicyService = new ClarificationPolicyService();
        ClarificationFailureHandler clarificationFailureHandler = new ClarificationFailureHandler(
                enricher,
                rawTransactionRepository,
                normalizedTransactionRepository,
                clarificationPolicyService
        );
        handler = new ClarificationPreparationHandler(
                clarificationGateway,
                rawTransactionRepository,
                clarificationFailureHandler,
                clarificationPolicyService
        );

        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("fetchFullReceiptForClarificationOrMarkFailure forwards maxAttempts=3 instead of hard-coded 1")
    void fetchFullReceiptForClarificationOrMarkFailureForwardsConfiguredMaxAttempts() {
        NormalizedTransaction normalizedTransaction = pendingClarification();
        RawTransaction rawTransaction = baseRaw();
        when(clarificationGateway.fetchFullReceipt(rawTransaction)).thenReturn(Optional.empty());

        Optional<?> firstAttempt = handler.fetchFullReceiptForClarificationOrMarkFailure(
                normalizedTransaction, rawTransaction, Instant.now(), 3
        );
        assertThat(firstAttempt).isEmpty();
        assertThat(normalizedTransaction.getClarificationAttempts()).isEqualTo(1);
        assertThat(normalizedTransaction.getStatus())
                .as("a single transient failure must not exhaust a 3-attempt budget")
                .isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);

        Optional<?> secondAttempt = handler.fetchFullReceiptForClarificationOrMarkFailure(
                normalizedTransaction, rawTransaction, Instant.now(), 3
        );
        assertThat(secondAttempt).isEmpty();
        assertThat(normalizedTransaction.getClarificationAttempts()).isEqualTo(2);
        assertThat(normalizedTransaction.getStatus())
                .as("second of three attempts must still not exhaust the budget")
                .isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);

        Optional<?> thirdAttempt = handler.fetchFullReceiptForClarificationOrMarkFailure(
                normalizedTransaction, rawTransaction, Instant.now(), 3
        );
        assertThat(thirdAttempt).isEmpty();
        assertThat(normalizedTransaction.getClarificationAttempts()).isEqualTo(3);
        assertThat(normalizedTransaction.getStatus())
                .as("the configured 3rd attempt must exhaust the budget")
                .isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(normalizedTransaction.getMissingDataReasons())
                .contains("CLARIFICATION_ATTEMPTS_EXHAUSTED");
    }

    @Test
    @DisplayName("fetchFullReceiptForReviewOrMarkFailure forwards maxAttempts=2 instead of hard-coded 1")
    void fetchFullReceiptForReviewOrMarkFailureForwardsConfiguredMaxAttempts() {
        NormalizedTransaction normalizedTransaction = needsReview();
        RawTransaction rawTransaction = baseRaw();
        when(clarificationGateway.fetchFullReceipt(rawTransaction)).thenReturn(Optional.empty());

        Optional<?> firstAttempt = handler.fetchFullReceiptForReviewOrMarkFailure(
                normalizedTransaction, rawTransaction, Instant.now(), 2
        );
        assertThat(firstAttempt).isEmpty();
        assertThat(normalizedTransaction.getFullReceiptClarificationAttempts()).isEqualTo(1);
        assertThat(normalizedTransaction.getStatus())
                .as("a single transient failure must not exhaust a 2-attempt budget")
                .isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);

        Optional<?> secondAttempt = handler.fetchFullReceiptForReviewOrMarkFailure(
                normalizedTransaction, rawTransaction, Instant.now(), 2
        );
        assertThat(secondAttempt).isEmpty();
        assertThat(normalizedTransaction.getFullReceiptClarificationAttempts()).isEqualTo(2);
        assertThat(normalizedTransaction.getStatus())
                .as("the configured 2nd attempt must exhaust the budget")
                .isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(normalizedTransaction.getMissingDataReasons())
                .contains("CLARIFICATION_ATTEMPTS_EXHAUSTED");
    }

    private static NormalizedTransaction pendingClarification() {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId("0xabc:ETHEREUM:" + WALLET);
        normalizedTransaction.setTxHash("0xabc");
        normalizedTransaction.setNetworkId(NetworkId.ETHEREUM);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(NormalizedTransactionType.SWAP);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        normalizedTransaction.setClassifiedBy(ClassificationSource.FUNCTION_NAME);
        normalizedTransaction.setConfidence(ConfidenceLevel.LOW);
        normalizedTransaction.setClarificationAttempts(0);
        normalizedTransaction.setFullReceiptClarificationAttempts(0);
        normalizedTransaction.setPricingAttempts(0);
        normalizedTransaction.setStatAttempts(0);
        normalizedTransaction.setMissingDataReasons(List.of());
        normalizedTransaction.setCreatedAt(Instant.parse("2026-03-19T10:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-19T10:00:00Z"));
        return normalizedTransaction;
    }

    private static NormalizedTransaction needsReview() {
        NormalizedTransaction normalizedTransaction = pendingClarification();
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setMissingDataReasons(List.of("ROUTER_METHOD_OVERLOAD_UNSUPPORTED"));
        return normalizedTransaction;
    }

    private static RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xabc:ETHEREUM:" + WALLET);
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", ROUTER)
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return rawTransaction;
    }
}
