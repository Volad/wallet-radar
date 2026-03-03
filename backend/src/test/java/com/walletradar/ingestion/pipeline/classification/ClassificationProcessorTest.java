package com.walletradar.ingestion.pipeline.classification;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.raw.RawSyncMethod;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.explorer.ExplorerProvider;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerReceipt;
import com.walletradar.ingestion.adapter.evm.explorer.model.ExplorerTransactionDetails;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.job.classification.ClassificationProcessor;
import com.walletradar.ingestion.job.classification.ConfidenceScorer;
import com.walletradar.ingestion.normalizer.NormalizedTransactionBuilder;
import com.walletradar.ingestion.store.IdempotentNormalizedTransactionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassificationProcessorTest {

    @Mock private RawTransactionRepository rawTransactionRepository;
    @Mock private TxClassifierDispatcher txClassifierDispatcher;
    @Mock private NormalizedTransactionBuilder normalizedTransactionBuilder;
    @Mock private IdempotentNormalizedTransactionStore idempotentNormalizedTransactionStore;
    @Mock private ExplorerProvider explorerProvider;

    private ClassificationProcessor processor;
    private ClassifierProperties classifierProperties;
    private ConfidenceScorer confidenceScorer;

    @BeforeEach
    void setUp() {
        classifierProperties = new ClassifierProperties();
        classifierProperties.setReceiptEnrichmentThreshold(0.85);
        classifierProperties.setNeedsReviewThreshold(0.60);
        classifierProperties.setReceiptEnrichmentMaxAttempts(3);
        classifierProperties.setReceiptEnrichmentBaseDelayMs(1);
        classifierProperties.setReceiptEnrichmentJitterFactor(0.0);
        confidenceScorer = new ConfidenceScorer();
        processor = new ClassificationProcessor(
                rawTransactionRepository,
                txClassifierDispatcher,
                normalizedTransactionBuilder,
                idempotentNormalizedTransactionStore,
                explorerProvider,
                classifierProperties,
                confidenceScorer
        );
    }

    @Test
    @DisplayName("processBatch classifies raw transactions and upserts events")
    void processBatch_classifiesRawTransactions() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("logs", List.of(
                        new org.bson.Document("address", "0xtoken")
                                .append("topics", List.of("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                        "0x0000000000000000000000000000000000000000000000000000000000001234",
                                        "0x0000000000000000000000000000000000000000000000000000000000005678"))
                                .append("data", "0x1")
                ));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc");
        raw.setNetworkId("ETHEREUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_TRANSFER_OUT);
        event.setWalletAddress("0xWALLET");
        event.setAssetContract("0xtoken");
        event.setAssetSymbol("TKN");
        event.setQuantityDelta(BigDecimal.ONE.negate());
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(event));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ETHEREUM, 1L, 200L, resolver, 12.0);

        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ETHEREUM,
                estimator);

        verify(idempotentNormalizedTransactionStore).upsert(any());
        verify(explorerProvider, never()).getReceipt(anyString(), any());
        verify(rawTransactionRepository).save(raw);
        assertThat(raw.getNormalizationStatus()).isEqualTo(NormalizationStatus.COMPLETE);
    }

    @Test
    @DisplayName("processBatch prefers raw timeStamp over block estimator for EVM")
    void processBatch_prefersRawTimestamp() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("timeStamp", "1759149768")
                .append("logs", List.of(
                        new org.bson.Document("address", "0xtoken")
                                .append("topics", List.of("0xd78ad95fa46c994b6551d0da85fc275fe613ce37657fb8d5e3d130840159d822"))
                ));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xrawts");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);

        RawClassifiedEvent sell = new RawClassifiedEvent();
        sell.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_SELL);
        sell.setWalletAddress("0xWALLET");
        sell.setAssetContract("0xtokenA");
        sell.setAssetSymbol("A");
        sell.setQuantityDelta(new BigDecimal("-1"));

        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_BUY);
        buy.setWalletAddress("0xWALLET");
        buy.setAssetContract("0xtokenB");
        buy.setAssetSymbol("B");
        buy.setQuantityDelta(BigDecimal.ONE);

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(sell, buy));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ARBITRUM, 1L, 200L, resolver, 12.0);

        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM, estimator);

        ArgumentCaptor<Instant> tsCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(normalizedTransactionBuilder).build(
                anyString(), any(), anyString(), tsCaptor.capture(), any(), any());
        assertThat(tsCaptor.getValue()).isEqualTo(Instant.ofEpochSecond(1_759_149_768L));
    }

    @Test
    @DisplayName("low-confidence classification triggers receipt enrichment and reclassification")
    void processBatch_lowConfidence_enrichesWithReceipt() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("value", "1000000")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        RawClassifiedEvent enrichedEvent = new RawClassifiedEvent();
        enrichedEvent.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        enrichedEvent.setWalletAddress("0xWALLET");
        enrichedEvent.setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        enrichedEvent.setAssetSymbol("USDC");
        enrichedEvent.setQuantityDelta(BigDecimal.ONE);

        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(), List.of(enrichedEvent));
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getReceipt("0xabc", NetworkId.ARBITRUM))
                .thenReturn(new ExplorerReceipt(new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of())));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ARBITRUM, 1L, 200L, resolver, 12.0);

        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM,
                estimator);

        verify(explorerProvider).getReceipt("0xabc", NetworkId.ARBITRUM);
        verify(txClassifierDispatcher, times(2)).classify(any(), anyString());
        assertThat(raw.getRawData().containsKey("logs")).isTrue();
        assertThat(raw.getBlockNumber()).isEqualTo(101L);
    }

    @Test
    @DisplayName("very low-confidence unresolved tx is marked NEEDS_REVIEW after receipt retries")
    void processBatch_lowConfidenceUnresolved_marksNeedsReview() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", "0xWALLET")
                                .append("value", "1")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xdead");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of());
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getReceipt("0xdead", NetworkId.ARBITRUM))
                .thenThrow(new RuntimeException("429 too many requests"))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(null);

        NormalizedTransaction built = new NormalizedTransaction();
        built.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(built);

        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ARBITRUM, 1L, 200L, resolver, 12.0);

        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM,
                estimator);

        verify(explorerProvider, times(3)).getReceipt("0xdead", NetworkId.ARBITRUM);
        ArgumentCaptor<NormalizedTransaction> txCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(idempotentNormalizedTransactionStore).upsert(txCaptor.capture());
        NormalizedTransaction saved = txCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(saved.getMissingDataReasons()).contains("LOW_CONFIDENCE_UNRESOLVED");
    }

    @Test
    @DisplayName("claim tx with synthetic-only evidence is capped and fast-pathed without receipt enrichment")
    void processBatch_claimInboundOnly_syntheticEvidence_fastPathSkipsReceiptEnrichment() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("methodId", "0x71ee95c0")
                .append("functionName", "claim(address[] users,address[] tokens,uint256[] amounts,bytes32[][] proofs)")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x912ce59144191c1204e64559fe8253a0e49e6548")
                                .append("from", "0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae")
                                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("value", "3332275903506388589")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xclaim");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        RawClassifiedEvent in1 = new RawClassifiedEvent();
        in1.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        in1.setWalletAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        in1.setAssetContract("0x912ce59144191c1204e64559fe8253a0e49e6548");
        in1.setAssetSymbol("ARB");
        in1.setQuantityDelta(new BigDecimal("3.332275903506388589"));

        RawClassifiedEvent in2 = new RawClassifiedEvent();
        in2.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        in2.setWalletAddress("0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f");
        in2.setAssetContract("0x40bd670a58238e6e230c430bbb5ce6ec0d40df48");
        in2.setAssetSymbol("MORPHO");
        in2.setQuantityDelta(new BigDecimal("0.270895013199732343"));

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(in1, in2));
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);

        NormalizedTransaction built = new NormalizedTransaction();
        built.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(built);

        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ARBITRUM, 1L, 200L, resolver, 12.0);

        processor.processBatch(List.of(raw), "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f", NetworkId.ARBITRUM, estimator);

        verify(explorerProvider, never()).getReceipt("0xclaim", NetworkId.ARBITRUM);
        ArgumentCaptor<BigDecimal> confidenceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(normalizedTransactionBuilder).build(
                anyString(), any(), anyString(), any(), any(), confidenceCaptor.capture());
        assertThat(confidenceCaptor.getValue()).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("processBatch with empty list returns no upserts")
    void processBatch_emptyList_noUpserts() {
        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(NetworkId.ETHEREUM, 1L, 100L, resolver, 12.0);

        processor.processBatch(List.of(), "0xWALLET", NetworkId.ETHEREUM,
                estimator);

        verify(idempotentNormalizedTransactionStore, never()).upsert(any());
    }

    @Test
    @DisplayName("BLOCKSCOUT low-confidence enrichment calls details before receipt")
    void processBatch_blockscout_enrichmentOrder_detailsThenReceipt() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("value", "1000000")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xblockscout");
        raw.setNetworkId("BASE");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent enrichedEvent = new RawClassifiedEvent();
        enrichedEvent.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        enrichedEvent.setWalletAddress("0xWALLET");
        enrichedEvent.setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        enrichedEvent.setAssetSymbol("USDC");
        enrichedEvent.setQuantityDelta(BigDecimal.ONE);

        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(), List.of(), List.of(enrichedEvent));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(explorerProvider.getTransactionDetails("0xblockscout", NetworkId.BASE))
                .thenReturn(new ExplorerTransactionDetails(new org.bson.Document("hash", "0xblockscout")
                        .append("blockNumber", "101")
                        .append("method", "multicall")));
        when(explorerProvider.getReceipt("0xblockscout", NetworkId.BASE))
                .thenReturn(new ExplorerReceipt(new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of())));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);

        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.BASE, estimator);

        var order = inOrder(explorerProvider);
        order.verify(explorerProvider).getTransactionDetails("0xblockscout", NetworkId.BASE);
        order.verify(explorerProvider).getReceipt("0xblockscout", NetworkId.BASE);
    }

    @Test
    @DisplayName("ETHERSCAN low-confidence enrichment skips details and fetches receipt only")
    void processBatch_etherscan_enrichmentOrder_receiptOnly() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                .append("value", "1000000")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xetherscan");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(), List.of());
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(explorerProvider.getReceipt("0xetherscan", NetworkId.ARBITRUM))
                .thenReturn(new ExplorerReceipt(new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of())));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.ARBITRUM);
        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM, estimator);

        verify(explorerProvider, never()).getTransactionDetails(anyString(), any());
        verify(explorerProvider).getReceipt("0xetherscan", NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("BLOCKSCOUT enrichment skips details fetch when details are already in rawData")
    void processBatch_blockscout_skipsDetailsFetchWhenAlreadyPresent() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("details", new org.bson.Document("hash", "0xhas-details"))
                        .append("tokenTransfers", List.of(
                                new org.bson.Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                        .append("from", "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f")
                                        .append("to", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f")
                                        .append("value", "1000000")
                        )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xhas-details");
        raw.setNetworkId("BASE");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(), List.of());
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(explorerProvider.getReceipt("0xhas-details", NetworkId.BASE))
                .thenReturn(new ExplorerReceipt(new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of())));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xhas-details", NetworkId.BASE);
        verify(explorerProvider).getReceipt("0xhas-details", NetworkId.BASE);
    }

    @Test
    @DisplayName("ETHERSCAN enrichment skips receipt fetch when explorer.receipt logs already exist")
    void processBatch_etherscan_skipsReceiptFetchWhenStoredReceiptExists() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("receipt", new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of(
                                new org.bson.Document("address", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                        .append("topics", List.of(
                                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                                                "0x00000000000000000000000068bc3b81c853338eaaa21552f57437dfd7bf5b7f",
                                                "0x0000000000000000000000001a87f12ac07e9746e9b053b8d7ef1d45270d693f"
                                        ))
                                        .append("data", "0x00000000000000000000000000000000000000000000000000000000000f4240")
                        ))));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xstored-receipt");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        RawClassifiedEvent enrichedEvent = new RawClassifiedEvent();
        enrichedEvent.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        enrichedEvent.setWalletAddress("0xWALLET");
        enrichedEvent.setAssetContract("0xaf88d065e77c8cc2239327c5edb3a432268e5831");
        enrichedEvent.setAssetSymbol("USDC");
        enrichedEvent.setQuantityDelta(BigDecimal.ONE);
        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(), List.of(enrichedEvent));
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.ARBITRUM);
        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM, estimator);

        verify(explorerProvider, never()).getReceipt("0xstored-receipt", NetworkId.ARBITRUM);
        assertThat(raw.getRawData().containsKey("logs")).isTrue();
    }

    @Test
    @DisplayName("ETHERSCAN enrichment skips receipt fetch when explorer.receipt exists without logs")
    void processBatch_etherscan_skipsReceiptFetchWhenStoredReceiptWithoutLogsExists() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("receipt", new org.bson.Document("blockNumber", "0x65")));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xstored-receipt-no-logs");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.ETHERSCAN);

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(), List.of());
        when(explorerProvider.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.ARBITRUM);
        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.ARBITRUM, estimator);

        verify(explorerProvider, never()).getReceipt("0xstored-receipt-no-logs", NetworkId.ARBITRUM);
    }

    @Test
    @DisplayName("synthetic-only logs cap confidence to avoid false high-confidence")
    void processBatch_syntheticOnlyLogs_capsConfidence() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .append("from", "0x1111111111111111111111111111111111111111")
                                .append("to", wallet)
                                .append("value", "1000000000000000000")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xsynthetic");
        raw.setNetworkId("ARBITRUM");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.RPC);

        RawClassifiedEvent swapSell = new RawClassifiedEvent();
        swapSell.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_SELL);
        swapSell.setAssetContract("0xTokenA");
        swapSell.setQuantityDelta(new BigDecimal("-1"));
        RawClassifiedEvent swapBuy = new RawClassifiedEvent();
        swapBuy.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_BUY);
        swapBuy.setAssetContract("0xTokenB");
        swapBuy.setQuantityDelta(new BigDecimal("1"));
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(swapSell, swapBuy));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.ARBITRUM);
        processor.processBatch(List.of(raw), wallet, NetworkId.ARBITRUM, estimator);

        ArgumentCaptor<BigDecimal> confidenceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(normalizedTransactionBuilder).build(anyString(), any(), anyString(), any(), any(), confidenceCaptor.capture());
        assertThat(confidenceCaptor.getValue()).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("low-signal tx short-circuits enrichment and is marked NEEDS_REVIEW")
    void processBatch_lowSignalTx_skipsEnrichmentAndMarksNeedsReview() {
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("methodId", "0x")
                .append("input", "0x12345678")
                .append("value", "0")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xlow-signal");
        raw.setNetworkId("BASE");
        raw.setWalletAddress("0xWALLET");
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of());
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        NormalizedTransaction built = new NormalizedTransaction();
        built.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(built);

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), "0xWALLET", NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xlow-signal", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xlow-signal", NetworkId.BASE);

        ArgumentCaptor<NormalizedTransaction> txCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(idempotentNormalizedTransactionStore).upsert(txCaptor.capture());
        NormalizedTransaction saved = txCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(saved.getMissingDataReasons())
                .contains("LOW_CONFIDENCE_UNRESOLVED", "LOW_SIGNAL_NO_ENRICHMENT_BENEFIT");
    }

    @Test
    @DisplayName("BLOCKSCOUT fast-path selector with synthetic-cap confidence skips details/receipt enrichment")
    void processBatch_blockscout_fastPathSkipsEnrichment_whenConfidenceAtCap() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", wallet)
                .append("to", "0x4200000000000000000000000000000000000006")
                .append("value", "10000000000000")
                .append("methodId", "0x")
                .append("input", "0xd0e30db0")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x4200000000000000000000000000000000000006")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", wallet)
                                .append("value", "10000000000000")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xfastpath");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent sell = new RawClassifiedEvent();
        sell.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_SELL);
        sell.setWalletAddress(wallet);
        sell.setAssetContract("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        sell.setQuantityDelta(new BigDecimal("-0.00001"));
        RawClassifiedEvent buy = new RawClassifiedEvent();
        buy.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.SWAP_BUY);
        buy.setWalletAddress(wallet);
        buy.setAssetContract("0x4200000000000000000000000000000000000006");
        buy.setQuantityDelta(new BigDecimal("0.00001"));
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(sell, buy));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xfastpath", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xfastpath", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT fast-path skips enrichment for LP multicall selector")
    void processBatch_blockscout_fastPathSkipsEnrichment_forLpMulticall() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("value", "0")
                .append("methodId", "0x")
                .append("input", "0xac9650d8")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("from", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                                .append("to", wallet)
                                .append("value", "897031359")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xfastpath-lp-multicall");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent lpExit = new RawClassifiedEvent();
        lpExit.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_EXIT);
        lpExit.setWalletAddress(wallet);
        lpExit.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        lpExit.setQuantityDelta(new BigDecimal("897.031359"));
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(lpExit));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xfastpath-lp-multicall", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xfastpath-lp-multicall", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT LP-sensitive multicall with generic transfer does not fast-path and enriches")
    void processBatch_blockscout_lpSensitiveMulticall_genericTransferForcesEnrichment() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("value", "262291541311233040")
                .append("methodId", "0x")
                .append("input", "0xac9650d8")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("from", wallet)
                                .append("to", "0x72ab388e2e2f6facef59e3c3fa2c4e29011c2d38")
                                .append("value", "1148838448")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xlp-sensitive");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent genericOut = new RawClassifiedEvent();
        genericOut.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_TRANSFER_OUT);
        genericOut.setWalletAddress(wallet);
        genericOut.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bdA02913");
        genericOut.setQuantityDelta(new BigDecimal("-1148.838448"));

        RawClassifiedEvent lpEntry = new RawClassifiedEvent();
        lpEntry.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_ENTRY);
        lpEntry.setWalletAddress(wallet);
        lpEntry.setAssetContract("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364");
        lpEntry.setQuantityDelta(BigDecimal.ONE);

        when(txClassifierDispatcher.classify(any(), anyString()))
                .thenReturn(List.of(genericOut), List.of(genericOut), List.of(lpEntry));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(explorerProvider.getTransactionDetails("0xlp-sensitive", NetworkId.BASE))
                .thenReturn(new ExplorerTransactionDetails(new org.bson.Document("hash", "0xlp-sensitive")));
        when(explorerProvider.getReceipt("0xlp-sensitive", NetworkId.BASE))
                .thenReturn(new ExplorerReceipt(new org.bson.Document("blockNumber", "0x65")
                        .append("logs", List.of())));
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider).getTransactionDetails("0xlp-sensitive", NetworkId.BASE);
        verify(explorerProvider).getReceipt("0xlp-sensitive", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT fast-path skips enrichment for synthetic inbound transfer selector")
    void processBatch_blockscout_fastPathSkipsEnrichment_forInboundTransfer() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", "0xbaed383ede0e5d9d72430661f3285daa77e9439f")
                .append("to", wallet)
                .append("value", "0")
                .append("methodId", "0xa9059cbb")
                .append("input", "0xa9059cbb")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("from", "0xbaed383ede0e5d9d72430661f3285daa77e9439f")
                                .append("to", wallet)
                                .append("value", "900513200")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xfastpath-inbound");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent inbound = new RawClassifiedEvent();
        inbound.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        inbound.setWalletAddress(wallet);
        inbound.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        inbound.setQuantityDelta(new BigDecimal("900.5132"));
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(inbound));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xfastpath-inbound", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xfastpath-inbound", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT fast-path skips enrichment for LP position entry semantic")
    void processBatch_blockscout_fastPathSkipsEnrichment_forLpPositionEntry() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", wallet)
                .append("to", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                .append("value", "0")
                .append("methodId", "0x")
                .append("input", "0xac9650d8")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364")
                                .append("from", "0x0000000000000000000000000000000000000000")
                                .append("to", wallet)
                                .append("value", "938761")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xfastpath-lp-position-entry");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent positionEntry = new RawClassifiedEvent();
        positionEntry.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.LP_POSITION_ENTRY);
        positionEntry.setWalletAddress(wallet);
        positionEntry.setAssetContract("0x46a15b0b27311cedf172ab29e4f4766fbe7f4364");
        positionEntry.setQuantityDelta(BigDecimal.ONE);
        positionEntry.setPositionId("938761");
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(positionEntry));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xfastpath-lp-position-entry", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xfastpath-lp-position-entry", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT fast-path skips enrichment for unknown selector when synthetic evidence is sufficient")
    void processBatch_blockscout_fastPathSkipsEnrichment_forUnknownSelectorWithSyntheticEvidence() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", "0xbaed383ede0e5d9d72430661f3285daa77e9439f")
                .append("to", wallet)
                .append("value", "0")
                .append("methodId", "0x")
                .append("input", "0xdeadbeef")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of(
                        new org.bson.Document("contractAddress", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913")
                                .append("from", "0xbaed383ede0e5d9d72430661f3285daa77e9439f")
                                .append("to", wallet)
                                .append("value", "900513200")
                )));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xfastpath-unknown-selector");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        RawClassifiedEvent inbound = new RawClassifiedEvent();
        inbound.setEventType(com.walletradar.domain.transaction.normalized.EconomicEventType.EXTERNAL_INBOUND);
        inbound.setWalletAddress(wallet);
        inbound.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        inbound.setQuantityDelta(new BigDecimal("900.5132"));
        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of(inbound));
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new NormalizedTransaction());

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xfastpath-unknown-selector", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xfastpath-unknown-selector", NetworkId.BASE);
    }

    @Test
    @DisplayName("BLOCKSCOUT low-signal self-transfer skips enrichment even with positive value")
    void processBatch_blockscout_lowSignalSelfTransfer_skipsEnrichment() {
        String wallet = "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f";
        org.bson.Document rawData = new org.bson.Document("blockNumber", "0x64")
                .append("from", wallet)
                .append("to", wallet)
                .append("methodId", "0x")
                .append("input", "0x6dea4157")
                .append("value", "74125988889806")
                .append("explorer", new org.bson.Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xlow-signal-self-transfer");
        raw.setNetworkId("BASE");
        raw.setWalletAddress(wallet);
        raw.setBlockNumber(100L);
        raw.setRawData(rawData);
        raw.setSyncMethod(RawSyncMethod.BLOCKSCOUT);

        when(txClassifierDispatcher.classify(any(), anyString())).thenReturn(List.of());
        when(explorerProvider.supports(NetworkId.BASE)).thenReturn(true);
        NormalizedTransaction built = new NormalizedTransaction();
        built.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        when(normalizedTransactionBuilder.build(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(built);

        EstimatingBlockTimestampResolver estimator = calibratedEstimator(NetworkId.BASE);
        processor.processBatch(List.of(raw), wallet, NetworkId.BASE, estimator);

        verify(explorerProvider, never()).getTransactionDetails("0xlow-signal-self-transfer", NetworkId.BASE);
        verify(explorerProvider, never()).getReceipt("0xlow-signal-self-transfer", NetworkId.BASE);
    }

    private static EstimatingBlockTimestampResolver calibratedEstimator(NetworkId networkId) {
        BlockTimestampResolver resolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId candidate) { return true; }

            @Override
            public Instant getBlockTimestamp(NetworkId candidate, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        EstimatingBlockTimestampResolver estimator = new EstimatingBlockTimestampResolver();
        estimator.calibrate(networkId, 1L, 200L, resolver, 12.0);
        return estimator;
    }
}
