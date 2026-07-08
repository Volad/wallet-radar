package com.walletradar.application.normalization.job.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.normalization.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.application.linking.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.application.linking.pipeline.clarification.PendingClarificationQueryService;
import com.walletradar.application.linking.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.application.linking.pipeline.clarification.RawTransactionClarificationEnricher;
import com.walletradar.application.linking.pipeline.clarification.ReceiptClarificationGateway;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataClarificationWorkflowHandlerTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN_A = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TOKEN_B = "0xcccccccccccccccccccccccccccccccccccccccc";

    @Mock
    private PendingClarificationQueryService pendingClarificationQueryService;
    @Mock
    private PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;
    @Mock
    private ReceiptClarificationGateway clarificationGateway;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    private MetadataClarificationWorkflowHandler service;

    @BeforeEach
    void setUp() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setBatchSize(10);
        properties.setRetryDelaySeconds(120);
        properties.setMaxAttempts(3);

        RawTransactionClarificationEnricher enricher = new RawTransactionClarificationEnricher();
        ClarificationPolicyService clarificationPolicyService = new ClarificationPolicyService();

        service = new MetadataClarificationWorkflowHandler(
                pendingClarificationQueryService,
                pendingReceiptClarificationQueryService,
                properties,
                enricher,
                new ClarificationFailureHandler(
                        enricher,
                        rawTransactionRepository,
                        normalizedTransactionRepository,
                        clarificationPolicyService
                ),
                new ClarificationReclassificationMarker(
                        normalizedTransactionRepository
                ),
                new ClarificationPreparationHandler(
                        clarificationGateway,
                        rawTransactionRepository,
                        new ClarificationFailureHandler(
                                enricher,
                                rawTransactionRepository,
                                normalizedTransactionRepository,
                                clarificationPolicyService
                        ),
                        clarificationPolicyService
                )
        );

        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("active needs-review rows use the unified full receipt pass and return to reclassification")
    void activeNeedsReviewRowsUseUnifiedFullReceiptPassAndReturnToReclassification() {
        NormalizedTransaction needsReview = pendingClarification(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        needsReview.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        needsReview.setClarificationAttempts(3);
        needsReview.setFullReceiptClarificationAttempts(0);
        RawTransaction rawTransaction = receiptCompleteInboundRaw();
        when(rawTransactionRepository.findById(needsReview.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.BLOCKSCOUT
                )));

        boolean clarified = service.clarify(needsReview);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(saved.getClarificationAttempts()).isEqualTo(3);
        assertThat(saved.getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("persisted full receipt evidence is reused before external receipt fetch")
    void persistedFullReceiptEvidenceIsReusedBeforeExternalReceiptFetch() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        RawTransaction rawTransaction = lowConfidenceSwapRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        "0x9999999999999999999999999999999999999999",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.BLOCKSCOUT
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
    }

    @Test
    @DisplayName("clarification enriches receipt status and gas then promotes to PENDING_PRICE")
    void clarificationEnrichesReceiptStatusAndGasThenPromotesToPendingPrice() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        RawTransaction rawTransaction = lowConfidenceSwapRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        "0x9999999999999999999999999999999999999999",
                        null,
                        List.of(new Document("address", ROUTER)),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1").append("logs", List.of(new Document("address", ROUTER))),
                        null
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getRawData().getString("txreceipt_status")).isEqualTo("1");
        assertThat(rawCaptor.getValue().getRawData().getString("gasUsed")).isEqualTo("21000");
        assertThat(rawCaptor.getValue().getRawData().getString("effectiveGasPrice")).isEqualTo("50000000000");
        assertThat(rawCaptor.getValue().getRawData().getString("contractAddress"))
                .isEqualTo("0x9999999999999999999999999999999999999999");
        assertThat(rawCaptor.getValue().getClarificationEvidence()).isNotNull();
        assertThat(rawCaptor.getValue().getClarificationEvidence().get("fullReceipt", Document.class))
                .containsEntry("status", "0x1");
        assertThat(rawCaptor.getValue().getRawData().containsKey("clarificationEvidence")).isFalse();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(saved.getClarificationAttempts()).isEqualTo(1);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getClassifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
    }

    @Test
    @DisplayName("clarification returns exhausted attempts to reclassification")
    void clarificationReturnsExhaustedAttemptsToReclassification() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        pending.setClarificationAttempts(2);

        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(lowConfidenceSwapRaw()));
        when(clarificationGateway.fetchFullReceipt(any(RawTransaction.class)))
                .thenReturn(Optional.empty());

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        verify(rawTransactionRepository).save(any(RawTransaction.class));

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(normalizedCaptor.getValue().getClarificationAttempts()).isEqualTo(3);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons())
                .contains(
                        "MISSING_EXECUTION_STATUS",
                        "MISSING_EFFECTIVE_GAS_PRICE",
                        "MISSING_GAS_USED",
                        "CLARIFICATION_FULL_RECEIPT_UNAVAILABLE",
                        "CLARIFICATION_ATTEMPTS_EXHAUSTED"
                );
    }

    @Test
    @DisplayName("synthetic logs do not change classification during clarification")
    void syntheticLogsDoNotChangeClassificationDuringClarification() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.VAULT_DEPOSIT);
        RawTransaction rawTransaction = lowConfidenceDepositWithSyntheticLogsRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        null
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();

        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        Document clarificationEvidence = rawCaptor.getValue().getClarificationEvidence();
        assertThat(clarificationEvidence).isNotNull();
        assertThat(clarificationEvidence.getString("sourceFamily")).isNull();
        assertThat(clarificationEvidence.get("receipt", Document.class))
                .containsEntry("txReceiptStatus", "1")
                .containsEntry("gasUsed", "21000")
                .containsEntry("effectiveGasPrice", "50000000000");
        assertThat(clarificationEvidence.get("fullReceipt", Document.class))
                .containsEntry("status", "0x1");
        assertThat(rawCaptor.getValue().getRawData().containsKey("clarificationEvidence")).isFalse();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
        assertThat(saved.getClassifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
    }

    @Test
    @DisplayName("clarification with complete raw evidence still returns to reclassification")
    void clarificationWithCompleteRawEvidenceStillReturnsToReclassification() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        RawTransaction rawTransaction = receiptCompleteInboundRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        null
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("clarification persists partial evidence and still returns to reclassification")
    void clarificationPersistsPartialEvidenceAndStillReturnsToReclassification() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        RawTransaction rawTransaction = lowConfidenceSwapRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchFullReceipt(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        null
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        assertThat(saved.getClarificationAttempts()).isEqualTo(1);
    }

    private static NormalizedTransaction pendingClarification(NormalizedTransactionType type) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId("0xabc:ETHEREUM:" + WALLET);
        normalizedTransaction.setTxHash("0xabc");
        normalizedTransaction.setNetworkId(NetworkId.ETHEREUM);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(type);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        normalizedTransaction.setClassifiedBy(ClassificationSource.FUNCTION_NAME);
        normalizedTransaction.setConfidence(ConfidenceLevel.LOW);
        normalizedTransaction.setClarificationAttempts(0);
        normalizedTransaction.setPricingAttempts(0);
        normalizedTransaction.setStatAttempts(0);
        normalizedTransaction.setMissingDataReasons(List.of());
        normalizedTransaction.setCreatedAt(Instant.parse("2026-03-19T10:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-19T10:00:00Z"));
        return normalizedTransaction;
    }

    private static RawTransaction lowConfidenceSwapRaw() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", ROUTER)
                .append("value", "0")
                .append("functionName", "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", ROUTER)
                                .append("value", "1000000"),
                        new Document("contractAddress", TOKEN_B)
                                .append("tokenSymbol", "ARB")
                                .append("tokenDecimal", "18")
                                .append("from", ROUTER)
                                .append("to", WALLET)
                                .append("value", "2000000000000000000")
                )).append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private static RawTransaction lowConfidenceDepositWithSyntheticLogsRaw() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", ROUTER)
                .append("value", "0")
                .append("functionName", "deposit(uint256)")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()))
                .append("logs", List.of(
                        new Document("address", TOKEN_A)
                                .append("topics", List.of("0xddf252ad"))
                                .append("__syntheticTransferLog", true)
                )));
        return rawTransaction;
    }

    private static RawTransaction receiptCompleteInboundRaw() {
        RawTransaction rawTransaction = baseRaw();
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", ROUTER)
                .append("to", WALLET)
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("gasPrice", "50000000000")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", TOKEN_A)
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", ROUTER)
                                .append("to", WALLET)
                                .append("value", "1000000")
                )).append("internalTransfers", List.of())));
        return rawTransaction;
    }

    private static RawTransaction baseRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0xabc:ETHEREUM:" + WALLET);
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress(WALLET);
        return rawTransaction;
    }
}
