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
import com.walletradar.ingestion.pipeline.classification.reason.ClassificationReasonCode;
import com.walletradar.ingestion.pipeline.classification.reason.ClarificationPolicyService;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryEntry;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryFamily;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryRole;
import com.walletradar.ingestion.pipeline.classification.registry.ProtocolRegistryService;
import com.walletradar.ingestion.pipeline.classification.support.ClarificationEligibilitySupport;
import com.walletradar.ingestion.pipeline.classification.support.CowSwapSupport;
import com.walletradar.ingestion.pipeline.classification.support.NativeAssetSymbolResolver;
import com.walletradar.ingestion.pipeline.clarification.ClarificationReceiptEnrichment;
import com.walletradar.ingestion.pipeline.clarification.PendingReceiptClarificationQueryService;
import com.walletradar.ingestion.pipeline.clarification.RelatedLifecycleDiscoveryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptClarificationWorkflowHandlerTest {

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
    private TrackedWalletLookupService trackedWalletLookupService;
    @Mock
    private RelatedLifecycleDiscoveryService relatedLifecycleDiscoveryService;

    private ReceiptClarificationWorkflowHandler service;

    @BeforeEach
    void setUp() {
        OnChainClarificationProperties properties = new OnChainClarificationProperties();
        properties.getFullReceipt().setBatchSize(10);
        properties.getFullReceipt().setMaxAttempts(1);
        properties.getFullReceipt().setRetryDelaySeconds(120);

        OnChainClassifier classifier = new OnChainClassifier(
                protocolRegistryService,
                trackedWalletLookupService,
                new NativeAssetSymbolResolver()
        );
        RawTransactionClarificationEnricher enricher = new RawTransactionClarificationEnricher();
        ClarificationPolicyService clarificationPolicyService = new ClarificationPolicyService();

        service = new ReceiptClarificationWorkflowHandler(
                pendingReceiptClarificationQueryService,
                properties,
                enricher,
                classifier,
                new ClarificationFailureHandler(
                        enricher,
                        rawTransactionRepository,
                        normalizedTransactionRepository,
                        clarificationPolicyService
                ),
                new ClarificationReclassificationHandler(
                        new OnChainNormalizedTransactionBuilder(),
                        normalizedTransactionRepository,
                        relatedLifecycleDiscoveryService
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
        lenient().when(normalizedTransactionRepository.saveAll(anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rawTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(protocolRegistryService.lookup(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("persisted full receipt evidence prevents redundant receipt clarification fetch")
    void persistedFullReceiptEvidencePreventsRedundantReceiptClarificationFetch() {
        NormalizedTransaction pending = reviewTx(
                "0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a",
                NetworkId.BASE,
                "ROUTER_METHOD_OVERLOAD_UNSUPPORTED"
        );
        RawTransaction rawTransaction = baseLpExitRaw();
        rawTransaction.setClarificationEvidence(new Document()
                .append("sourceFamily", "ETHERSCAN")
                .append("receipt", new Document("txReceiptStatus", "1")
                        .append("logs", List.of(new Document("address", POSITION_MANAGER)
                                .append("topics", List.of("0xlegacy")))))
                .append("transfers", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                .append("tokenSymbol", "WETH")
                                .append("tokenName", "Wrapped Ether")
                                .append("tokenDecimal", "18")
                                .append("from", POSITION_MANAGER)
                                .append("to", WALLET)
                                .append("value", "100000000000000000")
                ))));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        verifyNoInteractions(clarificationGateway);
        verify(rawTransactionRepository, never()).save(any(RawTransaction.class));
        verify(normalizedTransactionRepository, never()).save(any());
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
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
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
        Document clarificationEvidence = rawCaptor.getValue().getClarificationEvidence();
        assertThat(clarificationEvidence).isNotNull();
        assertThat(clarificationEvidence.getString("sourceFamily")).isEqualTo("ETHERSCAN");
        assertThat(clarificationEvidence.get("receipt", Document.class))
                .containsEntry("txReceiptStatus", "1")
                .containsEntry("gasUsed", "195742")
                .containsEntry("effectiveGasPrice", "4044313")
                .containsEntry("blockNumber", "0x2");
        assertThat(clarificationEvidence.get("fullReceipt", Document.class))
                .containsEntry("status", "0x1");
        assertThat(rawCaptor.getValue().getRawData().containsKey("clarificationEvidence")).isFalse();

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("nested linked-hash-map clarification evidence prevents redundant full receipt fetch")
    void nestedLinkedHashMapClarificationEvidencePreventsRedundantFullReceiptFetch() {
        NormalizedTransaction pending = reviewTx("0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a", NetworkId.BASE, "ROUTER_METHOD_OVERLOAD_UNSUPPORTED");
        pending.setFullReceiptClarificationAttempts(1);
        RawTransaction rawTransaction = baseLpExitRaw();
        rawTransaction.setClarificationEvidence(new Document()
                .append("fullReceiptClarificationAttempts", 1)
                .append("receipt", new LinkedHashMap<>(Map.of(
                        "txReceiptStatus", "1",
                        "logs", List.of(new LinkedHashMap<>(Map.of(
                                "address", POSITION_MANAGER,
                                "topics", List.of("0xlegacy")
                        )))
                )))
                .append("transfers", new LinkedHashMap<>(Map.of(
                        "tokenTransfers", List.of(new LinkedHashMap<>(Map.of(
                                "contractAddress", "0x4200000000000000000000000000000000000006",
                                "from", POSITION_MANAGER,
                                "to", WALLET,
                                "value", "1",
                                "tokenDecimal", "18",
                                "tokenSymbol", "WETH"
                        )))
                ))));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        verifyNoInteractions(clarificationGateway);
        verify(rawTransactionRepository, never()).save(any(RawTransaction.class));
        verify(normalizedTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("wrapper-only Euler row becomes explicit stop condition after full receipt clarification")
    void wrapperOnlyEulerRowBecomesExplicitStopConditionAfterFullReceiptClarification() {
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
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
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
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons()).containsExactly("STOP_CONDITION_WRAPPER_ONLY");
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("full receipt unavailable uses centralized receipt failure handler")
    void fullReceiptUnavailableUsesCentralizedReceiptFailureHandler() {
        NormalizedTransaction pending = reviewTx(
                "0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a",
                NetworkId.BASE,
                "ROUTER_METHOD_OVERLOAD_UNSUPPORTED"
        );
        RawTransaction rawTransaction = baseLpExitRaw();
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction)).thenReturn(Optional.empty());

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isFalse();
        ArgumentCaptor<RawTransaction> rawCaptor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getClarificationEvidence())
                .containsEntry("fullReceiptClarificationAttempts", 1)
                .containsEntry("lastFullReceiptClarificationFailureReason", "CLARIFICATION_FULL_RECEIPT_UNAVAILABLE");

        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons()).containsExactly(
                "ROUTER_METHOD_OVERLOAD_UNSUPPORTED",
                "CLARIFICATION_FULL_RECEIPT_UNAVAILABLE"
        );
    }

    @Test
    @DisplayName("pending-price bridge-out row can fetch full receipt evidence and stay BRIDGE_OUT")
    void pendingPriceBridgeOutRowCanFetchFullReceiptEvidenceAndStayBridgeOut() {
        String txHash = "0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98";
        String lifiDiamond = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae";
        NormalizedTransaction pending = pendingPriceBridgeTx(txHash, NetworkId.ARBITRUM);
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "15")
                .append("from", WALLET)
                .append("to", lifiDiamond)
                .append("methodId", "0x30c48952")
                .append("functionName", "swapAndStartBridgeTokensViaMayan(tuple _bridgeData,tuple[] _swapData,tuple _mayanData)")
                .append("input", "0x30c4895200000000000000000000000000000000000000000000000000000000")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", lifiDiamond)
                                .append("value", "1000000")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "240000",
                        "50000000",
                        null,
                        "0x2",
                        List.of(new Document("address", lifiDiamond)
                                .append("topics", List.of("0xbridge"))),
                        List.of(new Document("contractAddress", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", lifiDiamond)
                                .append("value", "1000000")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons())
                .doesNotContain(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED);
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("1inch outbound-only routed row can fetch full receipt evidence and reclassify to SWAP")
    void oneInchOutboundOnlyRoutedRowCanFetchFullReceiptEvidenceAndReclassifyToSwap() {
        String txHash = "0xcd5b0c2b99a04b52dbca149f25c979c1ff1f2ebb4a7db4b921daee9810091024";
        String oneInchRouter = "0x111111125421ca6dc452d289314280a0f8842a65";
        String maker = "0x4444444444444444444444444444444444444444";
        NormalizedTransaction pending = pendingPriceTx(txHash, NetworkId.BASE, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        pending.setProtocolName("1inch");
        pending.setMissingDataReasons(List.of(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code()));
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.BASE.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.BLOCKSCOUT);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "8")
                .append("from", WALLET)
                .append("to", oneInchRouter)
                .append("methodId", "0xe5d7bde6")
                .append("functionName", "fillOrder(tuple order,bytes signature,uint256 makingAmount,uint256 takingAmount,uint256 skipPermitAndThresholdAmount)")
                .append("input", "0xe5d7bde600000000000000000000000000000000000000000000000000000000")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", maker)
                                .append("value", "1000000")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "210000",
                        "1000000000",
                        null,
                        "0x2",
                        List.of(new Document("address", oneInchRouter)
                                .append("topics", List.of("0x1inch"))),
                        List.of(
                                new Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                        .append("tokenSymbol", "USDC")
                                        .append("tokenName", "USD Coin")
                                        .append("tokenDecimal", "6")
                                        .append("from", WALLET)
                                        .append("to", maker)
                                        .append("value", "1000000"),
                                new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                        .append("tokenSymbol", "WETH")
                                        .append("tokenName", "Wrapped Ether")
                                        .append("tokenDecimal", "18")
                                        .append("from", maker)
                                        .append("to", oneInchRouter)
                                        .append("value", "4684323603346941"),
                                new Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                        .append("tokenSymbol", "WETH")
                                        .append("tokenName", "Wrapped Ether")
                                        .append("tokenDecimal", "18")
                                        .append("from", oneInchRouter)
                                        .append("to", "0x0000000000000000000000000000000000000000")
                                        .append("value", "4684323603346941")
                        ),
                        List.of(new Document("from", oneInchRouter)
                                .append("to", WALLET)
                                .append("value", "4684323603346941")
                                .append("isError", "0")),
                        new Document("status", "0x1"),
                        RawSyncMethod.BLOCKSCOUT
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getFlows())
                .filteredOn(flow -> flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.SELL)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("USDC");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-1.000000");
                });
        assertThat(normalizedCaptor.getValue().getFlows())
                .filteredOn(flow -> flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("ETH");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.004684323603346941");
                });
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("1inch RPC withdrawal-log row can fetch full receipt evidence and reclassify to SWAP")
    void oneInchRpcWithdrawalLogRowCanFetchFullReceiptEvidenceAndReclassifyToSwap() {
        String txHash = "0x4d1b48bc6c3937f3379c59ee6eb048f7078eef9258c62a178b3cdb2d632bf42c";
        String oneInchRouter = "0x111111125421ca6dc452d289314280a0f8842a65";
        String intermediary = "0x8c864d0c8e476bf9eb9d620c10e1296fb0e2f940";
        String wrappedBnb = "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c";
        NormalizedTransaction pending = pendingPriceTx(txHash, NetworkId.BSC, NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        pending.setProtocolName("1inch");
        pending.setProtocolVersion("V6");
        pending.setMissingDataReasons(List.of(ClassificationReasonCode.ROUTED_AGGREGATOR_OUTBOUND_ONLY.code()));
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.BSC.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.RPC);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1762340007")
                .append("transactionIndex", "113")
                .append("from", WALLET)
                .append("to", oneInchRouter)
                .append("methodId", "0x07ed2379")
                .append("functionName", "")
                .append("input", oneInchSwapInput(
                        intermediary,
                        "0x61fac5f038515572d6f42d4bcb6b581642753d50",
                        "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        intermediary,
                        WALLET,
                        "12000000000000000000",
                        "1"
                ))
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x61fac5f038515572d6f42d4bcb6b581642753d50")
                                .append("tokenSymbol", "IN")
                                .append("tokenDecimal", "18")
                                .append("from", WALLET)
                                .append("to", intermediary)
                                .append("value", "12000000000000000000")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "210000",
                        "39794810",
                        null,
                        "0x2",
                        List.of(new Document("address", wrappedBnb)
                                .append("topics", List.of(
                                        "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65",
                                        "0x0000000000000000000000008c864d0c8e476bf9eb9d620c10e1296fb0e2f940"
                                ))
                                .append("data", "0x00000000000000000000000000000000000000000000000000047ee4aac4401b")),
                        List.of(
                                new Document("contractAddress", "0x61fac5f038515572d6f42d4bcb6b581642753d50")
                                        .append("tokenSymbol", "IN")
                                        .append("tokenName", "INFINIT")
                                        .append("tokenDecimal", "18")
                                        .append("from", WALLET)
                                        .append("to", intermediary)
                                        .append("value", "12000000000000000000"),
                                new Document("contractAddress", wrappedBnb)
                                        .append("tokenSymbol", "WBNB")
                                        .append("tokenName", "Wrapped BNB")
                                        .append("tokenDecimal", "18")
                                        .append("from", "0xc4dc171d499b3f5340bffed8433bddcec8d33b04")
                                        .append("to", intermediary)
                                        .append("value", "1265420489474075")
                        ),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.RPC
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.SWAP);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons()).isEmpty();
        assertThat(normalizedCaptor.getValue().getFlows())
                .filteredOn(flow -> flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.SELL)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("IN");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("-12.000000000000000000");
                });
        assertThat(normalizedCaptor.getValue().getFlows())
                .filteredOn(flow -> flow.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY)
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.getAssetSymbol()).isEqualTo("BNB");
                    assertThat(flow.getQuantityDelta()).isEqualByComparingTo("0.001265420489474075");
                });
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("GMX deposit request pending clarification row can fetch full receipt evidence and remain pending clarification")
    void gmxDepositRequestPendingClarificationRowCanFetchFullReceiptEvidenceAndRemainPendingClarification() {
        String txHash = "0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28";
        NormalizedTransaction pending = pendingClarificationTx(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.LP_ENTRY_REQUEST,
                "GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED",
                "GMX"
        );
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "5")
                .append("from", WALLET)
                .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                .append("methodId", "0xac9650d8")
                .append("functionName", "multicall(bytes[] data)")
                .append("input", "0xac9650d8000000007d39aaf100000000e6d66ac8000000004c82aa41")
                .append("value", "104501240797800")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                                .append("value", "1000000000")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "796770",
                        "17032000",
                        null,
                        "0x2",
                        List.of(new Document("address", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                                .append("topics", List.of("0xrequest"))),
                        List.of(new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", WALLET)
                                .append("to", "0x1c3fa76e6e1088bce750f23a5bfcffa1efef6a41")
                                .append("value", "1000000000")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY_REQUEST);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        assertThat(normalizedCaptor.getValue().getMissingDataReasons()).contains("GMX_DEPOSIT_REQUEST_CORRELATION_REQUIRED");
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("CoW settlement pending clarification row can fetch full receipt evidence and become pending price")
    void cowSettlementPendingClarificationRowCanFetchFullReceiptEvidenceAndBecomePendingPrice() {
        RawTransaction request = new RawTransaction();
        request.setId("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105:ARBITRUM:" + WALLET);
        request.setTxHash("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105");
        request.setNetworkId(NetworkId.ARBITRUM.name());
        request.setWalletAddress(WALLET);
        request.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("methodId", "0x322bba21")
                .append("input", cowEthFlowCreateOrderInput())
                .append("value", "27638811423349461")
                .append("txreceipt_status", "1"));
        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView.wrap(request)
        );

        String txHash = "0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0";
        NormalizedTransaction pending = pendingClarificationTx(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.DEX_ORDER_SETTLEMENT,
                "COW_ORDER_SETTLEMENT_CORRELATION_REQUIRED",
                "CoW Swap"
        );
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000050")
                .append("transactionIndex", "9")
                .append("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                .append("methodId", "0x13d79a0b")
                .append("functionName", "settle(bytes32[][],uint256[],bytes32[],bytes)")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                                .append("tokenSymbol", "wstETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("to", WALLET)
                                .append("value", "22742145033450122")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "210000",
                        "1000000000",
                        null,
                        "0x2",
                        List.of(new Document("address", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("topics", List.of("0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"))
                                .append("data", cowTradeLogData(expectedCorrelation))),
                        List.of(new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                                .append("tokenSymbol", "wstETH")
                                .append("tokenName", "Wrapped liquid staked Ether 2.0")
                                .append("tokenDecimal", "18")
                                .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("to", WALLET)
                                .append("value", "22742145033450122")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelation);
        assertThat(normalizedCaptor.getValue().getProtocolName()).isEqualTo("CoW Swap");
        assertThat(normalizedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
        verify(rawTransactionRepository).save(any(RawTransaction.class));
    }

    @Test
    @DisplayName("active CoW settlement candidate with blank top-level context can fetch receipt and reclassify")
    void activeCowSettlementCandidateWithBlankTopLevelContextCanFetchReceiptAndReclassify() {
        RawTransaction request = new RawTransaction();
        request.setId("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105:ARBITRUM:" + WALLET);
        request.setTxHash("0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105");
        request.setNetworkId(NetworkId.ARBITRUM.name());
        request.setWalletAddress(WALLET);
        request.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xba3cb449bd2b4adddbc894d8697f5170800eadec")
                .append("methodId", "0x322bba21")
                .append("input", cowEthFlowCreateOrderInput())
                .append("value", "27638811423349461")
                .append("txreceipt_status", "1"));
        String expectedCorrelation = CowSwapSupport.resolveEthFlowCorrelationId(
                com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView.wrap(request)
        );

        String txHash = "0xd7abb9c0e819f445c2b806e1eddf9e69560b9c423765162b196a3c5fa8a678e0";
        NormalizedTransaction pending = pendingPriceTx(txHash, NetworkId.ARBITRUM, NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000050")
                .append("transactionIndex", "9")
                .append("methodId", "0x13d79a0b")
                .append("input", "deprecated")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tx", new Document()
                        .append("methodId", "0x13d79a0b")
                        .append("functionName", "")
                        .append("to", "")
                        .append("from", ""))
                        .append("tokenTransfers", List.of(
                                new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                                        .append("tokenSymbol", "wstETH")
                                        .append("tokenDecimal", "18")
                                        .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                        .append("to", WALLET)
                                        .append("value", "22742145033450122")
                        ))
                        .append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "210000",
                        "1000000000",
                        null,
                        "0x2",
                        List.of(new Document("address", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("topics", List.of("0xa07a543ab8a018198e99ca0184c93fe9050a79400a0a723441f84de1d972cc17"))
                                .append("data", cowTradeLogData(expectedCorrelation))),
                        List.of(new Document("contractAddress", "0x5979d7b546e38e414f7e9822514be443a4800529")
                                .append("tokenSymbol", "wstETH")
                                .append("tokenName", "Wrapped liquid staked Ether 2.0")
                                .append("tokenDecimal", "18")
                                .append("from", "0x9008d19f58aabd9ed0d60971565aa8510560ab41")
                                .append("to", WALLET)
                                .append("value", "22742145033450122")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.DEX_ORDER_SETTLEMENT);
        assertThat(normalizedCaptor.getValue().getStatus()).isIn(
                NormalizedTransactionStatus.PENDING_PRICE,
                NormalizedTransactionStatus.PENDING_CLARIFICATION
        );
        assertThat(normalizedCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelation);
    }

    @Test
    @DisplayName("active GMX pool exit settlement candidate can fetch receipt and reclassify")
    void activeGmxPoolExitSettlementCandidateCanFetchReceiptAndReclassify() {
        String requestKey = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String txHash = "0x977474f616af6a4227237ec7680f8c2023b7c626652ffda2349ba71f76cfb00e";
        NormalizedTransaction pending = pendingPriceTx(txHash, NetworkId.ARBITRUM, NormalizedTransactionType.VAULT_WITHDRAW);
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000100")
                .append("transactionIndex", "11")
                .append("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                .append("methodId", "0xc96fea9f")
                .append("functionName", "executeWithdrawal(bytes32 key,tuple oracleParams)")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1")
                                .append("tokenSymbol", "WETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", WALLET)
                                .append("value", "15000000000000000"),
                        new Document("contractAddress", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1")
                                .append("tokenSymbol", "WETH")
                                .append("tokenDecimal", "18")
                                .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", WALLET)
                                .append("value", "25000000000000000")
                )).append("internalTransfers", List.of(
                        new Document("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", WALLET)
                                .append("value", "1230000000000000")
                                .append("isError", "0")
                ))));
        List<Document> receiptLogs = List.of(
                new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                        .append("topics", List.of(
                                "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                "0x2d9c403df9b86f59d7b6d67ebcf89f79db157535b6874548305b7e8314f822d4",
                                requestKey,
                                "0x0000000000000000000000001111111111111111111111111111111111111111"
                        ))
                        .append("eventName", "WithdrawalExecuted")
                        .append("data", "0x")
        );
        List<Document> tokenTransfers = List.of(
                new Document("contractAddress", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1")
                        .append("tokenSymbol", "WETH")
                        .append("tokenName", "Wrapped Ether")
                        .append("tokenDecimal", "18")
                        .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "15000000000000000")
        );
        List<Document> internalTransfers = List.of(
                new Document("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                        .append("to", WALLET)
                        .append("value", "1230000000000000")
                        .append("isError", "0")
        );
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "310000",
                        "1500000000",
                        null,
                        "0x2",
                        receiptLogs,
                        tokenTransfers,
                        internalTransfers,
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));

        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_SETTLEMENT);
        assertThat(normalizedCaptor.getValue().getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(normalizedCaptor.getValue().getCorrelationId()).isEqualTo(requestKey);
    }

    @Test
    @DisplayName("GMX terminal clarification reclassifies row but leaves lifecycle linking to dedicated phase")
    void gmxTerminalClarificationDefersLifecycleLinkingToDedicatedPhase() {
        String mainOrderKey = "0x8185a2694ec51cbc7ef47531e08ede8b4eacd55f7f866f6b00b5625f9c047de5";
        String siblingOrderKey = "0x3231f64e29aa6dbdbd9457215cfd7ef9b8c22e2fc71ea669d01df7f1fa4398b9";
        String txHash = "0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8";
        NormalizedTransaction pending = pendingClarificationTx(
                txHash,
                NetworkId.ARBITRUM,
                NormalizedTransactionType.DERIVATIVE_ORDER_EXECUTION,
                "GMX_DERIVATIVE_EXECUTION_EVIDENCE_REQUIRED",
                "GMX"
        );
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setId(pending.getId());
        rawTransaction.setTxHash(txHash);
        rawTransaction.setNetworkId(NetworkId.ARBITRUM.name());
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setSyncMethod(RawSyncMethod.ETHERSCAN);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000200")
                .append("transactionIndex", "13")
                .append("from", "0x3333333333333333333333333333333333333333")
                .append("to", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                .append("methodId", "0x7ebc83f7")
                .append("functionName", "executeOrder(bytes32 key,tuple oracleParams)")
                .append("txreceipt_status", "1")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", WALLET)
                                .append("value", "9667735")
                )).append("internalTransfers", List.of())));
        when(rawTransactionRepository.findById(pending.getId())).thenReturn(Optional.of(rawTransaction));
        when(clarificationGateway.fetchReceiptWithTransferEvidence(rawTransaction))
                .thenReturn(Optional.of(new ClarificationReceiptEnrichment(
                        "1",
                        "310000",
                        "1500000000",
                        null,
                        "0x2",
                        List.of(
                                new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                        .append("topics", List.of(
                                                "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                                "0x01",
                                                mainOrderKey,
                                                "0x0000000000000000000000001111111111111111111111111111111111111111"
                                        ))
                                        .append("data", "0x" + asciiHex("PositionDecrease OrderExecuted")),
                                new Document("address", "0xc8ee91a54287db53897056e12d9819156d3822fb")
                                        .append("topics", List.of(
                                                "0x468a25a7ba624ceea6e540ad6f49171b52495b648417ae91bca21676d8a24dc5",
                                                "0x01",
                                                siblingOrderKey,
                                                "0x0000000000000000000000001111111111111111111111111111111111111111"
                                        ))
                                        .append("data", "0x" + asciiHex("OrderCancelled AUTO_CANCEL"))
                        ),
                        List.of(new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenName", "USD Coin")
                                .append("tokenDecimal", "6")
                                .append("from", "0x70d95587d40a2caf56bd97485ab3eec10bee6336")
                                .append("to", WALLET)
                                .append("value", "9667735")),
                        List.of(),
                        new Document("status", "0x1"),
                        RawSyncMethod.ETHERSCAN
                )));
        boolean clarified = service.clarify(pending);

        assertThat(clarified).isTrue();
        ArgumentCaptor<NormalizedTransaction> normalizedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(normalizedTransactionRepository).save(normalizedCaptor.capture());
        verify(normalizedTransactionRepository, never()).saveAll(anyCollection());
        verify(relatedLifecycleDiscoveryService).discoverAndNormalize(any(), any());
        assertThat(normalizedCaptor.getValue().getType()).isEqualTo(NormalizedTransactionType.DERIVATIVE_POSITION_DECREASE);
        assertThat(normalizedCaptor.getValue().getMatchedCounterparty()).isNull();
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
        verify(clarificationGateway, never()).fetchReceiptWithTransferEvidence(any());
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

    private static NormalizedTransaction pendingClarificationTx(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type,
            String reason,
            String protocolName
    ) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        normalizedTransaction.setTxHash(txHash);
        normalizedTransaction.setNetworkId(networkId);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(type);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setConfidence(ConfidenceLevel.MEDIUM);
        normalizedTransaction.setProtocolName(protocolName);
        normalizedTransaction.setMissingDataReasons(List.of(reason));
        normalizedTransaction.setClarificationAttempts(0);
        normalizedTransaction.setFullReceiptClarificationAttempts(0);
        normalizedTransaction.setPricingAttempts(0);
        normalizedTransaction.setStatAttempts(0);
        normalizedTransaction.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        return normalizedTransaction;
    }

    private static NormalizedTransaction pendingPriceTx(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionType type
    ) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        normalizedTransaction.setTxHash(txHash);
        normalizedTransaction.setNetworkId(networkId);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(type);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setConfidence(ConfidenceLevel.MEDIUM);
        normalizedTransaction.setMissingDataReasons(List.of());
        normalizedTransaction.setClarificationAttempts(0);
        normalizedTransaction.setFullReceiptClarificationAttempts(0);
        normalizedTransaction.setPricingAttempts(0);
        normalizedTransaction.setStatAttempts(0);
        normalizedTransaction.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        normalizedTransaction.setUpdatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        return normalizedTransaction;
    }

    private static NormalizedTransaction pendingPriceBridgeTx(String txHash, NetworkId networkId) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(txHash + ":" + networkId + ":" + WALLET);
        normalizedTransaction.setTxHash(txHash);
        normalizedTransaction.setNetworkId(networkId);
        normalizedTransaction.setWalletAddress(WALLET);
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.ofEpochSecond(1_700_000_000L));
        normalizedTransaction.setTransactionIndex(15);
        normalizedTransaction.setType(NormalizedTransactionType.BRIDGE_OUT);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        normalizedTransaction.setClassifiedBy(ClassificationSource.METHOD_ID);
        normalizedTransaction.setConfidence(ConfidenceLevel.MEDIUM);
        normalizedTransaction.setMissingDataReasons(List.of(ClarificationEligibilitySupport.BRIDGE_PAIR_EVIDENCE_REQUIRED));
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

    private static String cowEthFlowCreateOrderInput() {
        return "0x322bba21"
                + paddedAddress("0x5979d7b546e38e414f7e9822514be443a4800529")
                + paddedAddress(WALLET)
                + paddedUint("27638811423349461")
                + paddedUint("22628189600680790")
                + paddedBytes32("0xd7e27923e5a18d36851057738a3970e4ddd2905e85b5fc19436a160647863fff")
                + paddedUint("0")
                + paddedUint("1760524229")
                + paddedBool(false)
                + paddedUint("58228845");
    }

    private static String cowTradeLogData(String orderDigest) {
        return "0x"
                + paddedAddress("0x0000000000000000000000000000000000000001")
                + paddedAddress("0x5979d7b546e38e414f7e9822514be443a4800529")
                + paddedUint("27638811423349461")
                + paddedUint("22742145033450122")
                + paddedUint("0")
                + paddedUint("192")
                + paddedUint("32")
                + strip0x(orderDigest);
    }

    private static String paddedAddress(String address) {
        return "%064x".formatted(new java.math.BigInteger(strip0x(address), 16));
    }

    private static String oneInchSwapInput(
            String executor,
            String srcToken,
            String dstToken,
            String srcReceiver,
            String dstReceiver,
            String amount,
            String minReturnAmount
    ) {
        return "0x07ed2379"
                + paddedAddress(executor)
                + paddedAddress(srcToken)
                + paddedAddress(dstToken)
                + paddedAddress(srcReceiver)
                + paddedAddress(dstReceiver)
                + paddedUint(amount)
                + paddedUint(minReturnAmount)
                + paddedUint("0")
                + paddedUint("288")
                + paddedUint("0");
    }

    private static String paddedUint(String value) {
        return "%064x".formatted(new java.math.BigInteger(value));
    }

    private static String paddedBytes32(String value) {
        String normalized = strip0x(value);
        return "0".repeat(Math.max(0, 64 - normalized.length())) + normalized;
    }

    private static String paddedBool(boolean value) {
        return value ? "0".repeat(63) + "1" : "0".repeat(64);
    }

    private static String strip0x(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }

    private static String asciiHex(String value) {
        StringBuilder hex = new StringBuilder();
        for (char character : value.toCharArray()) {
            hex.append(String.format("%02x", (int) character));
        }
        return hex.toString();
    }
}
