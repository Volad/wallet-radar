package com.walletradar.ingestion.job.clarification;

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
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.special.ProtocolSpecialHandlerDispatcher;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.ExplorerReceiptClarificationGateway;
import com.walletradar.ingestion.pipeline.clarification.PendingClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import com.walletradar.ingestion.pipeline.onchain.OnChainNormalizedTransactionBuilder;
import com.walletradar.ingestion.wallet.query.TrackedWalletLookupService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainClarificationServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ROUTER = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN_A = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TOKEN_B = "0xcccccccccccccccccccccccccccccccccccccccc";

    @Mock
    private PendingClarificationQueryService pendingClarificationQueryService;
    @Mock
    private ExplorerReceiptClarificationGateway clarificationGateway;
    @Mock
    private RawTransactionRepository rawTransactionRepository;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private ProtocolRegistryService protocolRegistryService;
    @Mock
    private ProtocolSpecialHandlerDispatcher protocolSpecialHandlerDispatcher;
    @Mock
    private TrackedWalletLookupService trackedWalletLookupService;

    private OnChainClarificationService service;

    @BeforeEach
    void setUp() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.setBatchSize(10);
        properties.setRetryDelaySeconds(120);
        properties.setMaxAttempts(3);

        OnChainClassifier classifier = new OnChainClassifier(
                protocolRegistryService,
                protocolSpecialHandlerDispatcher,
                trackedWalletLookupService,
                new NativeAssetSymbolResolver()
        );

        service = new OnChainClarificationService(
                pendingClarificationQueryService,
                properties,
                clarificationGateway,
                new RawTransactionClarificationEnricher(),
                rawTransactionRepository,
                classifier,
                new OnChainNormalizedTransactionBuilder(),
                normalizedTransactionRepository
        );

        lenient().when(normalizedTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("clarification enriches receipt status and gas then promotes to PENDING_PRICE")
    void clarificationEnrichesReceiptStatusAndGasThenPromotesToPendingPrice() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        RawTransaction rawTransaction = lowConfidenceSwapRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetch("0xabc", NetworkId.ETHEREUM))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "21000",
                        "50000000000",
                        "0x9999999999999999999999999999999999999999"
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

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getClarificationAttempts()).isEqualTo(1);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(saved.getClassifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(saved.getFlows())
                .filteredOn(flow -> flow.getRole() == NormalizedLegRole.FEE)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo(new BigDecimal("-0.00105"));
                });
    }

    @Test
    @DisplayName("clarification stops after max attempts and marks transaction NEEDS_REVIEW")
    void clarificationStopsAfterMaxAttemptsAndMarksNeedsReview() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.SWAP);
        pending.setClarificationAttempts(2);

        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(lowConfidenceSwapRaw()));
        when(clarificationGateway.fetch("0xabc", NetworkId.ETHEREUM)).thenReturn(Optional.empty());

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        verify(rawTransactionRepository, never()).save(any(RawTransaction.class));

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(normalizedCaptor.getValue().getClarificationAttempts()).isEqualTo(3);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons())
                .contains("CLARIFICATION_RECEIPT_UNAVAILABLE", "CLARIFICATION_ATTEMPTS_EXHAUSTED");
    }

    @Test
    @DisplayName("synthetic logs do not change classification during clarification")
    void syntheticLogsDoNotChangeClassificationDuringClarification() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.VAULT_DEPOSIT);
        RawTransaction rawTransaction = lowConfidenceDepositWithSyntheticLogsRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetch("0xabc", NetworkId.ETHEREUM))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment("1", "21000", "50000000000", null)));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.VAULT_DEPOSIT);
        assertThat(saved.getClassifiedBy()).isEqualTo(ClassificationSource.FUNCTION_NAME);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    @DisplayName("clarification short-circuits when transaction is no longer receipt-clarifiable")
    void clarificationShortCircuitsWhenTransactionIsNoLongerReceiptClarifiable() {
        NormalizedTransaction pending = pendingClarification(NormalizedTransactionType.EXTERNAL_INBOUND);
        RawTransaction rawTransaction = receiptCompleteInboundRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        verifyNoInteractions(clarificationGateway);
        verify(rawTransactionRepository, never()).save(any(RawTransaction.class));

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        NormalizedTransaction saved = normalizedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_INBOUND);
        assertThat(saved.getClarificationAttempts()).isEqualTo(0);
        assertThat(saved.getMissingDataReasons()).isEmpty();
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
