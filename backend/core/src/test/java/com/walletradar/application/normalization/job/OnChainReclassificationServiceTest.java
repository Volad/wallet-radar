package com.walletradar.application.normalization.job;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassifier;
import com.walletradar.application.normalization.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.application.linking.pipeline.clarification.CounterpartyEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.ProtocolNameEnrichmentService;
import com.walletradar.application.linking.pipeline.clarification.RegistryBridgeInboundTypeCorrectionService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.application.normalization.pipeline.onchain.PendingReclassificationQueryService;
import com.walletradar.session.application.AccountingUniverseService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainReclassificationServiceTest {

    @Mock
    private PendingReclassificationQueryService pendingReclassificationQueryService;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private OnChainClassifier onChainClassifier;
    @Mock
    private ProtocolNameEnrichmentService protocolNameEnrichmentService;
    @Mock
    private CounterpartyEnrichmentService counterpartyEnrichmentService;
    @Mock
    private RegistryBridgeInboundTypeCorrectionService registryBridgeInboundTypeCorrectionService;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private OnChainNormalizationProperties properties;
    private OnChainClarificationProperties clarificationProperties;
    private OnChainReclassificationService service;

    @BeforeEach
    void setUp() {
        properties = new OnChainNormalizationProperties();
        properties.setBatchSize(2);
        clarificationProperties = new OnChainClarificationProperties();
        clarificationProperties.setMaxAttempts(3);
        service = new OnChainReclassificationService(
                pendingReclassificationQueryService,
                rawTransactionRepository,
                normalizedTransactionRepository,
                properties,
                clarificationProperties,
                onChainClassifier,
                new OnChainNormalizedTransactionBuilder(),
                protocolNameEnrichmentService,
                registryBridgeInboundTypeCorrectionService,
                counterpartyEnrichmentService,
                accountingUniverseService
        );
    }

    @Test
    @DisplayName("reclassification runs normal classifier and saves resolved canonical row")
    void reclassificationRunsNormalClassifierAndSavesResolvedRow() {
        NormalizedTransaction existing = pendingReclassification("0xabc:ETHEREUM:0xwallet");
        RawTransaction rawTransaction = raw("0xabc");
        OnChainClassificationResult classification = classification(
                NormalizedTransactionType.SWAP,
                NormalizedTransactionStatus.PENDING_PRICE
        );
        when(pendingReclassificationQueryService.loadNextBatch(2)).thenReturn(List.of(existing));
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification);
        when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        int processed = service.processNextBatch();

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getPricingAttempts()).isEqualTo(4);
        assertThat(saved.getStatAttempts()).isEqualTo(2);
        assertThat(saved.getClientId()).isEqualTo("client-1");
        verify(protocolNameEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
        verify(counterpartyEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
    }

    @Test
    @DisplayName("pending clarification result is saved without enrichment or lifecycle discovery")
    void pendingClarificationResultSkipsPostClassificationEnrichment() {
        NormalizedTransaction existing = pendingReclassification("0xabc:ETHEREUM:0xwallet");
        RawTransaction rawTransaction = raw("0xabc");
        OnChainClassificationResult classification = classification(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.PENDING_CLARIFICATION
        );
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification);
        when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reclassified = service.reclassify(existing);

        assertThat(reclassified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        verify(protocolNameEnrichmentService, never()).enrichInPlace(any(), any(), any());
        verify(counterpartyEnrichmentService, never()).enrichInPlace(any(), any(), any());
    }

    @Test
    @DisplayName("exhausted pending clarification result is terminalized to review")
    void exhaustedPendingClarificationResultIsTerminalizedToReview() {
        NormalizedTransaction existing = pendingReclassification("0xabc:ETHEREUM:0xwallet");
        RawTransaction rawTransaction = raw("0xabc");
        rawTransaction.setClarificationEvidence(new Document("clarificationAttempts", 3));
        OnChainClassificationResult classification = classification(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.PENDING_CLARIFICATION
        );
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification);
        when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reclassified = service.reclassify(existing);

        assertThat(reclassified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(saved.getMissingDataReasons())
                .contains("MISSING_RECEIPT", ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code());
        verify(protocolNameEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
        verify(counterpartyEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
    }

    @Test
    @DisplayName("receipt-only clarification residual with replayable flows continues to pricing")
    void receiptOnlyClarificationResidualWithReplayableFlowsContinuesToPricing() {
        NormalizedTransaction existing = pendingReclassification("0xabc:ETHEREUM:0xwallet");
        RawTransaction rawTransaction = raw("0xabc");
        rawTransaction.setClarificationEvidence(new Document("clarificationAttempts", 1));
        OnChainClassificationResult classification = classification(
                NormalizedTransactionType.LP_ENTRY,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                List.of(flow(NormalizedLegRole.SELL, "USDC", "-100")),
                List.of(ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code())
        );
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification);
        when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reclassified = service.reclassify(existing);

        assertThat(reclassified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(saved.getMissingDataReasons())
                .contains(
                        ClassificationReasonCode.LP_POSITION_CORRELATION_REQUIRED.code(),
                        ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code()
                );
        verify(protocolNameEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
        verify(counterpartyEnrichmentService).enrichInPlace(saved, rawTransaction, saved.getUpdatedAt());
    }

    @Test
    @DisplayName("Euler decoder residual with replayable flows continues to pricing after receipt clarification")
    void eulerDecoderResidualWithReplayableFlowsContinuesToPricingAfterReceiptClarification() {
        NormalizedTransaction existing = pendingReclassification("0xabc:ETHEREUM:0xwallet");
        RawTransaction rawTransaction = raw("0xabc");
        rawTransaction.setClarificationEvidence(new Document("clarificationAttempts", 1));
        OnChainClassificationResult classification = classification(
                NormalizedTransactionType.UNKNOWN,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                List.of(
                        flow(NormalizedLegRole.TRANSFER, "eUSDC-29", "-2094.631504"),
                        flow(NormalizedLegRole.TRANSFER, "USDC", "2106.730523")
                ),
                List.of(ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code())
        );
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.of(rawTransaction));
        when(onChainClassifier.classify(rawTransaction)).thenReturn(classification);
        when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reclassified = service.reclassify(existing);

        assertThat(reclassified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getMissingDataReasons())
                .contains(
                        ClassificationReasonCode.EULER_BATCH_DECODER_REQUIRED.code(),
                        ClassificationReasonCode.CLARIFICATION_ATTEMPTS_EXHAUSTED.code()
                );
    }

    @Test
    @DisplayName("missing raw evidence sends pending reclassification row to review")
    void missingRawEvidenceSendsRowToReview() {
        NormalizedTransaction existing = pendingReclassification("missing:ETHEREUM:0xwallet");
        existing.setMissingDataReasons(List.of("EXISTING_REASON"));
        when(rawTransactionRepository.findById(existing.getId())).thenReturn(Optional.empty());

        boolean reclassified = service.reclassify(existing);

        assertThat(reclassified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(saved.getMissingDataReasons())
                .contains("EXISTING_REASON", ClassificationReasonCode.RAW_TRANSACTION_MISSING.code());
        verify(onChainClassifier, never()).classify(any());
        verify(protocolNameEnrichmentService, never()).enrichInPlace(any(), any(), any());
        verify(counterpartyEnrichmentService, never()).enrichInPlace(any(), any(), any());
    }

    private static NormalizedTransaction pendingReclassification(String id) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(id);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_RECLASSIFICATION);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-01-01T00:01:00Z"));
        normalizedTransaction.setPricingAttempts(4);
        normalizedTransaction.setStatAttempts(2);
        normalizedTransaction.setClientId("client-1");
        return normalizedTransaction;
    }

    private static RawTransaction raw(String txHash) {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(txHash + ":ETHEREUM:0xwallet");
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress("0xwallet");
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "5"));
        return rawTransaction;
    }

    private static OnChainClassificationResult classification(
            NormalizedTransactionType type,
            NormalizedTransactionStatus status
    ) {
        return classification(
                type,
                status,
                List.of(),
                status == NormalizedTransactionStatus.PENDING_CLARIFICATION
                        ? List.of("MISSING_RECEIPT")
                        : List.of()
        );
    }

    private static OnChainClassificationResult classification(
            NormalizedTransactionType type,
            NormalizedTransactionStatus status,
            List<NormalizedTransaction.Flow> flows,
            List<String> missingDataReasons
    ) {
        return new OnChainClassificationResult(
                type,
                status,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                flows,
                missingDataReasons,
                null,
                false,
                null,
                false,
                null,
                status == NormalizedTransactionStatus.PENDING_CLARIFICATION ? null : "Uniswap",
                null
        );
    }

    private static NormalizedTransaction.Flow flow(NormalizedLegRole role, String assetSymbol, String quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        return flow;
    }
}
