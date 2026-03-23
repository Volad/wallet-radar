package com.walletradar.ingestion.job.clarification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.config.OnChainClarificationProperties;
import com.walletradar.ingestion.pipeline.classification.OnChainClassifier;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.special.ProtocolSpecialHandlerDispatcher;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.clarification.ClarificationMode;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RawTransactionClarificationEnricher;
import com.walletradar.ingestion.pipeline.clarification.ReceiptClarificationGateway;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainReceiptClarificationServiceTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String POSITION_MANAGER = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";

    @Mock
    private PendingReceiptClarificationQueryService pendingReceiptClarificationQueryService;
    @Mock
    private ReceiptClarificationGateway clarificationGateway;
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

    private OnChainReceiptClarificationService service;

    @BeforeEach
    void setUp() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.getFullReceipt().setBatchSize(10);
        properties.getFullReceipt().setMaxAttempts(1);
        properties.getFullReceipt().setRetryDelaySeconds(120);

        OnChainClassifier classifier = new OnChainClassifier(
                protocolRegistryService,
                protocolSpecialHandlerDispatcher,
                trackedWalletLookupService,
                new NativeAssetSymbolResolver()
        );
        service = new OnChainReceiptClarificationService(
                pendingReceiptClarificationQueryService,
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
    @DisplayName("allowlisted Base LP exit row closes after full receipt clarification transfer enrichment")
    void allowlistedBaseLpExitRowClosesAfterFullReceiptClarificationTransferEnrichment() {
        NormalizedTransaction pending = reviewTx("0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a", NetworkId.BASE, "ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
        RawTransaction rawTransaction = baseLpExitRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(protocolRegistryService.lookup(NetworkId.BASE, POSITION_MANAGER))
                .thenReturn(Optional.of(new ProtocolRegistryEntry(
                        POSITION_MANAGER,
                        Set.of(NetworkId.BASE),
                        ProtocolRegistryFamily.DEX,
                        ProtocolRegistryRole.POSITION_MANAGER,
                        null,
                        ConfidenceLevel.HIGH,
                        "Pancake",
                        "Infinity",
                        false,
                        null
                )));
        when(clarificationGateway.fetch(rawTransaction, ClarificationMode.FULL_RECEIPT))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "195742",
                        "4044313",
                        null,
                        "0x2",
                        List.of(new Document("address", POSITION_MANAGER)
                                .append("topics", List.of("0x26f6a048ee9138f2c0ce266f322cb99228e8d619ae2bff30c67f8dcf9d2377b4"))),
                        List.of(new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                .append("tokenSymbol", "WETH")
                                .append("tokenName", "Wrapped Ether")
                                .append("tokenDecimal", "18")
                                .append("from", POSITION_MANAGER)
                                .append("to", WALLET)
                                .append("value", "100000000000000000")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        Document clarificationEvidence = rawCaptor.getValue().getRawData().get("clarificationEvidence", Document.class);
        assertThat(clarificationEvidence).isNotNull();
        assertThat(clarificationEvidence.getString("sourceFamily")).isEqualTo("ETHERSCAN");
        assertThat(clarificationEvidence.get("receipt", Document.class))
                .containsEntry("txReceiptStatus", "1")
                .containsEntry("gasUsed", "195742")
                .containsEntry("effectiveGasPrice", "4044313")
                .containsEntry("blockNumber", "0x2");
        assertThat(clarificationEvidence.get("fullReceipt", Document.class))
                .containsEntry("status", "0x1");

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("receipt-insufficient Euler row stays explicit review after full receipt clarification")
    void receiptInsufficientEulerRowStaysExplicitReviewAfterFullReceiptClarification() {
        NormalizedTransaction pending = reviewTx("0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2", NetworkId.AVALANCHE, "CLASSIFICATION_FAILED");
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(pending.getTxHash());
        rawTransaction.setNetworkId(NetworkId.AVALANCHE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xdddddddddddddddddddddddddddddddddddddddd")
                .append("methodId", "0xc16ae7a4")
                .append("functionName", "batch(tuple[] items)")
                .append("input", "0xc16ae7a400000000000000000000000000000000000000000000000000000000"));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetch(rawTransaction, ClarificationMode.FULL_RECEIPT))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "69159",
                        "30000000",
                        null,
                        "0x2",
                        List.of(new Document("address", "0xddcbe30a761edd2e19bba930a977475265f36fa1")
                                .append("topics", List.of("0xf022705c827017c972043d1984cfddc7958c9f4685b4d9ce8bd68696f4381cd2"))),
                        List.of(),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons()).contains("CLASSIFICATION_FAILED");
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("non allowlisted review row does not enter full receipt clarification")
    void nonAllowlistedReviewRowDoesNotEnterFullReceiptClarification() {
        NormalizedTransaction pending = reviewTx("0xabc", NetworkId.ETHEREUM, "HANDLER_UNSUPPORTED_METHOD");
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId(NetworkId.ETHEREUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("methodId", "0x12345678")
                .append("input", "0x12345678000000000000000000000000"));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        verify(clarificationGateway, never()).fetch(any(), any());
        verify(normalizedTransactionRepository, never()).save(any());
    }

    private static NormalizedTransaction reviewTx(String txHash, NetworkId networkId, String reason) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        normalizedTransaction.setTxHash(txHash);
        normalizedTransaction.setNetworkId(networkId);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setConfidence(ConfidenceLevel.LOW);
        normalizedTransaction.setMissingDataReasons(List.of(reason));
        normalizedTransaction.setClarificationAttempts(0);
        normalizedTransaction.setFullReceiptClarificationAttempts(0);
        normalizedTransaction.setPricingAttempts(0);
        normalizedTransaction.setStatAttempts(0);
        normalizedTransaction.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        return normalizedTransaction;
    }

    private static RawTransaction baseLpExitRaw() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId("0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a:BASE:" + WALLET);
        rawTransaction.setTxHash("0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a");
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "72")
                .append("from", WALLET)
                .append("to", POSITION_MANAGER)
                .append("methodId", "0x")
                .append("input", "0xac9650d8000000000000000000000000000000000000000000000000000000000c49ccbefc6f7865")
                .append("value", "0")
                .append("txreceipt_status", "1"));
        return rawTransaction;
    }
}
