package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.evm.EstimatingBlockTimestampResolver;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.job.InlineSwapPriceEnricher;
import com.walletradar.ingestion.normalizer.EconomicEventNormalizer;
import com.walletradar.ingestion.normalizer.GasCostCalculator;
import com.walletradar.ingestion.store.IdempotentEventStore;
import com.walletradar.pricing.HistoricalPriceResolverChain;
import com.walletradar.pricing.PriceResolutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassificationProcessorTest {

    @Mock private RawTransactionRepository rawTransactionRepository;
    @Mock private TxClassifierDispatcher txClassifierDispatcher;
    @Mock private IdempotentEventStore idempotentEventStore;
    @Mock private HistoricalPriceResolverChain historicalPriceResolverChain;

    private ClassificationProcessor processor;

    @BeforeEach
    void setUp() {
        EconomicEventNormalizer normalizer = new EconomicEventNormalizer(new GasCostCalculator());
        InlineSwapPriceEnricher enricher = new InlineSwapPriceEnricher(new com.walletradar.common.StablecoinRegistry());
        processor = new ClassificationProcessor(
                rawTransactionRepository,
                txClassifierDispatcher,
                normalizer,
                enricher,
                idempotentEventStore,
                historicalPriceResolverChain
        );
        when(historicalPriceResolverChain.resolve(any())).thenReturn(PriceResolutionResult.unknown());
    }

    @Test
    @DisplayName("processSegment reads raw from repo and classifies")
    void processSegment_classifiesRawTransactions() {
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

        when(rawTransactionRepository.findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(
                anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(raw));

        RawClassifiedEvent event = new RawClassifiedEvent();
        event.setEventType(com.walletradar.domain.EconomicEventType.EXTERNAL_TRANSFER_OUT);
        event.setWalletAddress("0xWALLET");
        event.setAssetContract("0xtoken");
        event.setAssetSymbol("TKN");
        event.setQuantityDelta(BigDecimal.ONE.negate());
        when(txClassifierDispatcher.classify(any(), anyString(), any())).thenReturn(List.of(event));

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

        Map<java.time.LocalDate, BigDecimal> nativePriceCache = new ConcurrentHashMap<>();
        AtomicLong processed = new AtomicLong(0);

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM,
                1L, 100L, estimator, nativePriceCache, "0xeth",
                Set.of("0xWALLET"), processed, 100L,
                (pct, lastBlock, msg) -> {});

        verify(idempotentEventStore).upsert(any());
    }

    @Test
    @DisplayName("processSegment with empty raw returns no upserts")
    void processSegment_emptyRaw_noUpserts() {
        when(rawTransactionRepository.findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(
                anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());

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

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM,
                1L, 100L, estimator, new ConcurrentHashMap<>(), "0xeth",
                Set.of("0xWALLET"), new AtomicLong(0), 100L,
                (pct, lastBlock, msg) -> {});

        verify(idempotentEventStore, never()).upsert(any());
    }
}
