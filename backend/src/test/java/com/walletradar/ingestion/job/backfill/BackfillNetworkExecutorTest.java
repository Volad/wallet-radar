package com.walletradar.ingestion.job.backfill;

import com.walletradar.common.StablecoinRegistry;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.classifier.TxClassifierDispatcher;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.job.DeferredPriceResolutionJob;
import com.walletradar.ingestion.job.InlineSwapPriceEnricher;
import com.walletradar.ingestion.job.SyncProgressTracker;
import com.walletradar.ingestion.normalizer.EconomicEventNormalizer;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackfillNetworkExecutorTest {

    @Mock private BackfillProperties backfillProperties;
    @Mock private IngestionNetworkProperties ingestionNetworkProperties;
    @Mock private TxClassifierDispatcher txClassifierDispatcher;
    @Mock private EconomicEventNormalizer economicEventNormalizer;
    @Mock private HistoricalPriceResolverChain historicalPriceResolverChain;
    @Mock private IdempotentEventStore idempotentEventStore;
    @Mock private StablecoinRegistry stablecoinRegistry;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private SyncProgressTracker syncProgressTracker;
    @Mock private DeferredPriceResolutionJob deferredPriceResolutionJob;
    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private RawTransactionRepository rawTransactionRepository;

    private RecordingNetworkAdapter recordingAdapter;
    private BackfillNetworkExecutor executor;

    @BeforeEach
    void setUp() {
        recordingAdapter = new RecordingNetworkAdapter(5000);

        RawFetchSegmentProcessor rawFetchSegmentProcessor = new RawFetchSegmentProcessor(rawTransactionRepository);
        InlineSwapPriceEnricher inlineSwapPriceEnricher = new InlineSwapPriceEnricher(stablecoinRegistry);
        ClassificationProcessor classificationProcessor = new ClassificationProcessor(
                rawTransactionRepository,
                txClassifierDispatcher,
                economicEventNormalizer,
                inlineSwapPriceEnricher,
                idempotentEventStore,
                historicalPriceResolverChain
        );

        executor = new BackfillNetworkExecutor(
                rawFetchSegmentProcessor,
                classificationProcessor,
                backfillProperties,
                ingestionNetworkProperties,
                syncProgressTracker,
                deferredPriceResolutionJob,
                applicationEventPublisher,
                syncStatusRepository
        );

        when(syncStatusRepository.findAll()).thenReturn(List.of(syncStatus("0xWALLET")));
        when(syncStatusRepository.findByWalletAddressAndNetworkId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(historicalPriceResolverChain.resolve(any()))
                .thenReturn(PriceResolutionResult.unknown());
        when(rawTransactionRepository.findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(
                anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("parallelSegments=2 splits block range into two segments covering the full range")
    void parallelSegments_splitsRangeCorrectly() {
        when(backfillProperties.getParallelSegments()).thenReturn(2);
        when(backfillProperties.getWindowBlocks()).thenReturn(20_000L);

        BlockHeightResolver heightResolver = new StubBlockHeightResolver(20_000L);
        BlockTimestampResolver timestampResolver = new StubBlockTimestampResolver();

        executor.runBackfillForNetwork("0xWALLET", NetworkId.ETHEREUM,
                recordingAdapter, heightResolver, timestampResolver);

        List<long[]> calls = recordingAdapter.fetchCalls;
        assertThat(calls).isNotEmpty();

        long minFrom = calls.stream().mapToLong(c -> c[0]).min().orElse(-1);
        long maxTo = calls.stream().mapToLong(c -> c[1]).max().orElse(-1);
        assertThat(minFrom).isEqualTo(1L);
        assertThat(maxTo).isEqualTo(20_000L);

        long totalBlocksCovered = calls.stream().mapToLong(c -> c[1] - c[0] + 1).sum();
        assertThat(totalBlocksCovered).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("small range (< 10000 blocks) runs sequentially regardless of parallelSegments")
    void smallRange_runsSequentially() {
        when(backfillProperties.getParallelSegments()).thenReturn(4);
        when(backfillProperties.getWindowBlocks()).thenReturn(5_000L);

        BlockHeightResolver heightResolver = new StubBlockHeightResolver(5_000L);
        BlockTimestampResolver timestampResolver = new StubBlockTimestampResolver();

        executor.runBackfillForNetwork("0xWALLET", NetworkId.ETHEREUM,
                recordingAdapter, heightResolver, timestampResolver);

        List<long[]> calls = recordingAdapter.fetchCalls;
        assertThat(calls).isNotEmpty();

        for (int i = 1; i < calls.size(); i++) {
            assertThat(calls.get(i)[0])
                    .as("Batch %d should start right after previous batch ends", i)
                    .isEqualTo(calls.get(i - 1)[1] + 1);
        }

        long totalBlocksCovered = calls.stream().mapToLong(c -> c[1] - c[0] + 1).sum();
        assertThat(totalBlocksCovered).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("parallelSegments=1 runs sequentially even for large ranges")
    void parallelSegmentsOne_runsSequentially() {
        when(backfillProperties.getParallelSegments()).thenReturn(1);
        when(backfillProperties.getWindowBlocks()).thenReturn(30_000L);

        BlockHeightResolver heightResolver = new StubBlockHeightResolver(30_000L);
        BlockTimestampResolver timestampResolver = new StubBlockTimestampResolver();

        executor.runBackfillForNetwork("0xWALLET", NetworkId.ETHEREUM,
                recordingAdapter, heightResolver, timestampResolver);

        List<long[]> calls = recordingAdapter.fetchCalls;
        assertThat(calls).isNotEmpty();

        for (int i = 1; i < calls.size(); i++) {
            assertThat(calls.get(i)[0])
                    .as("Batch %d should start right after previous batch ends", i)
                    .isEqualTo(calls.get(i - 1)[1] + 1);
        }
    }

    @Test
    @DisplayName("parallel segments cover the entire block range without gaps or overlaps")
    void parallelSegments_noGapsOrOverlaps() {
        when(backfillProperties.getParallelSegments()).thenReturn(4);
        when(backfillProperties.getWindowBlocks()).thenReturn(50_000L);

        BlockHeightResolver heightResolver = new StubBlockHeightResolver(50_000L);
        BlockTimestampResolver timestampResolver = new StubBlockTimestampResolver();

        executor.runBackfillForNetwork("0xWALLET", NetworkId.ETHEREUM,
                recordingAdapter, heightResolver, timestampResolver);

        List<long[]> calls = recordingAdapter.fetchCalls;
        assertThat(calls).isNotEmpty();

        long totalBlocks = calls.stream().mapToLong(c -> c[1] - c[0] + 1).sum();
        assertThat(totalBlocks).isEqualTo(50_000L);

        long minFrom = calls.stream().mapToLong(c -> c[0]).min().orElse(-1);
        long maxTo = calls.stream().mapToLong(c -> c[1]).max().orElse(-1);
        assertThat(minFrom).isEqualTo(1L);
        assertThat(maxTo).isEqualTo(50_000L);
    }

    // --- Test helpers ---

    private static SyncStatus syncStatus(String walletAddress) {
        SyncStatus s = new SyncStatus();
        s.setWalletAddress(walletAddress);
        s.setNetworkId("ETHEREUM");
        return s;
    }

    private static class RecordingNetworkAdapter implements NetworkAdapter {
        final CopyOnWriteArrayList<long[]> fetchCalls = new CopyOnWriteArrayList<>();
        private final int batchSize;

        RecordingNetworkAdapter(int batchSize) {
            this.batchSize = batchSize;
        }

        @Override
        public boolean supports(NetworkId networkId) {
            return networkId == NetworkId.ETHEREUM;
        }

        @Override
        public List<RawTransaction> fetchTransactions(String walletAddress, NetworkId networkId,
                                                       long fromBlock, long toBlock) {
            fetchCalls.add(new long[]{fromBlock, toBlock});
            return Collections.emptyList();
        }

        @Override
        public int getMaxBlockBatchSize() {
            return batchSize;
        }
    }

    private static class StubBlockHeightResolver implements BlockHeightResolver {
        private final long currentBlock;

        StubBlockHeightResolver(long currentBlock) { this.currentBlock = currentBlock; }

        @Override
        public boolean supports(NetworkId networkId) { return true; }

        @Override
        public long getCurrentBlock(NetworkId networkId) { return currentBlock; }
    }

    private static class StubBlockTimestampResolver implements BlockTimestampResolver {
        @Override
        public boolean supports(NetworkId networkId) { return true; }

        @Override
        public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
            return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
        }
    }
}
