package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.config.BackfillSegmentConfiguration;
import com.walletradar.ingestion.config.BackfillSegmentsConfiguration;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.sync.progress.SyncProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackfillNetworkExecutorTest {

    private static final String WALLET = "0xWALLET";
    private static final String NETWORK = "ETHEREUM";
    private static final String SYNC_ID = "sync-1";

    @Mock private RawFetchSegmentProcessor rawFetchSegmentProcessor;
    @Mock private BackfillProperties backfillProperties;
    @Mock private IngestionNetworkProperties ingestionNetworkProperties;
    @Mock private SyncProgressTracker syncProgressTracker;
    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private BackfillSegmentRepository backfillSegmentRepository;
    @Mock private NetworkAdapter networkAdapter;

    private BackfillNetworkExecutor executor;
    private Map<String, BackfillSegment> segments;

    @BeforeEach
    void setUp() {
        executor = new BackfillNetworkExecutor(
                rawFetchSegmentProcessor,
                backfillProperties,
                ingestionNetworkProperties,
                syncProgressTracker,
                syncStatusRepository,
                backfillSegmentRepository
        );

        segments = new ConcurrentHashMap<>();

        SyncStatus sync = new SyncStatus();
        sync.setId(SYNC_ID);
        sync.setWalletAddress(WALLET);
        sync.setNetworkId(NETWORK);
        sync.setLastBlockSynced(null);

        when(syncStatusRepository.findByWalletAddressAndNetworkId(WALLET, NETWORK)).thenReturn(Optional.of(sync));
        when(backfillProperties.getWindowBlocks()).thenReturn(100L);
        when(backfillProperties.getSegments()).thenReturn(segmentProfiles(2, 2, 180_000L, 6, 4, 120_000L));
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of());

        wireSegmentRepository();
    }

    @Test
    @DisplayName("creates segment plan, executes all segments and finalizes sync")
    void createsSegmentsAndCompletes() {
        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        List<BackfillSegment> all = findAllSegments();
        assertThat(all).hasSize(2);
        assertThat(all)
                .extracting(BackfillSegment::getFromBlock, BackfillSegment::getToBlock)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 50L), org.assertj.core.groups.Tuple.tuple(51L, 100L));
        assertThat(all).allMatch(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE);

        verify(rawFetchSegmentProcessor, times(2)).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));
        verify(syncProgressTracker).setRawFetchComplete(WALLET, NETWORK, 100L);
        verify(syncProgressTracker).setComplete(WALLET, NETWORK);
    }

    @Test
    @DisplayName("recovers stale RUNNING segments and retries them")
    void recoversStaleRunningSegments() {
        BackfillSegment staleRunning = segment(0, 1L, 50L, BackfillSegment.SegmentStatus.RUNNING);
        staleRunning.setUpdatedAt(Instant.now().minusSeconds(400));
        putSegment(staleRunning);

        BackfillSegment done = segment(1, 51L, 100L, BackfillSegment.SegmentStatus.COMPLETE);
        done.setProgressPct(100);
        done.setLastProcessedBlock(100L);
        putSegment(done);

        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        List<BackfillSegment> all = findAllSegments();
        assertThat(all).hasSize(2);
        assertThat(all).allMatch(s -> s.getStatus() == BackfillSegment.SegmentStatus.COMPLETE);

        verify(rawFetchSegmentProcessor, times(1)).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                eq(1L), eq(50L), any(BackfillProgressCallback.class));
        verify(syncProgressTracker).setRawFetchComplete(WALLET, NETWORK, 100L);
        verify(syncProgressTracker).setComplete(WALLET, NETWORK);
    }

    @Test
    @DisplayName("marks sync failed when one or more segments fail")
    void failedSegmentMarksSyncFailed() {
        doThrow(new RuntimeException("boom"))
                .when(rawFetchSegmentProcessor)
                .processSegment(anyString(), any(NetworkId.class), any(NetworkAdapter.class),
                        anyLong(), anyLong(), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        List<BackfillSegment> all = findAllSegments();
        assertThat(all).isNotEmpty();
        assertThat(all).anyMatch(s -> s.getStatus() == BackfillSegment.SegmentStatus.FAILED);

        verify(syncProgressTracker).setFailed(eq(WALLET), eq(NETWORK), contains("segments failed"));
    }

    @Test
    @DisplayName("resumes segment from lastProcessedBlock + 1 after restart")
    void resumesFromLastProcessedBlock() {
        BackfillSegment partial = segment(0, 1L, 50L, BackfillSegment.SegmentStatus.PENDING);
        partial.setProgressPct(50);
        partial.setLastProcessedBlock(25L);
        putSegment(partial);

        BackfillSegment done = segment(1, 51L, 100L, BackfillSegment.SegmentStatus.COMPLETE);
        done.setProgressPct(100);
        done.setLastProcessedBlock(100L);
        putSegment(done);

        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        verify(rawFetchSegmentProcessor, times(1)).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                eq(26L), eq(50L), any(BackfillProgressCallback.class));

        BackfillSegment resumed = segments.get(SYNC_ID + ":0");
        assertThat(resumed.getStatus()).isEqualTo(BackfillSegment.SegmentStatus.COMPLETE);
        assertThat(resumed.getLastProcessedBlock()).isEqualTo(50L);
        assertThat(resumed.getProgressPct()).isEqualTo(100);
    }

    @Test
    @DisplayName("uses by-rpc segment profile for RPC sync method")
    void usesByRpcSegmentProfileWhenNetworkIsRpc() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        entry.setBatchBlockSize(500);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of(NETWORK, entry));
        when(backfillProperties.getSegments()).thenReturn(segmentProfiles(2, 2, 180_000L, 3, 1, 120_000L));

        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegmentWithBlockCheckpoints(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), eq(500), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(90L),
                new NoopTimestampResolver()
        );

        List<BackfillSegment> all = findAllSegments();
        assertThat(all).hasSize(3);
        assertThat(all)
                .extracting(BackfillSegment::getFromBlock, BackfillSegment::getToBlock)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0L, 30L),
                        org.assertj.core.groups.Tuple.tuple(31L, 60L),
                        org.assertj.core.groups.Tuple.tuple(61L, 90L)
                );
        verify(rawFetchSegmentProcessor, times(3)).processSegmentWithBlockCheckpoints(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), eq(500), any(BackfillProgressCallback.class));
        verify(rawFetchSegmentProcessor, never()).processSegment(
                anyString(), any(NetworkId.class), any(NetworkAdapter.class),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));
    }

    @Test
    @DisplayName("falls back to defaults when by-rpc profile is missing or invalid")
    void fallsBackToDefaultsWhenByRpcIsInvalid() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC);
        entry.setBatchBlockSize(500);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of(NETWORK, entry));
        when(backfillProperties.getSegments()).thenReturn(segmentProfiles(2, 2, 180_000L, 0, 0, 0L));

        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegmentWithBlockCheckpoints(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), eq(500), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        List<BackfillSegment> all = findAllSegments();
        assertThat(all).hasSize(2);
        assertThat(all)
                .extracting(BackfillSegment::getFromBlock, BackfillSegment::getToBlock)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 50L), org.assertj.core.groups.Tuple.tuple(51L, 100L));
    }

    @Test
    @DisplayName("keeps legacy segment processing for non-RPC sync methods")
    void keepsLegacyProcessingForNonRpc() {
        IngestionNetworkProperties.NetworkIngestionEntry entry = new IngestionNetworkProperties.NetworkIngestionEntry();
        entry.setSyncMethod(IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.ETHERSCAN);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of(NETWORK, entry));

        doAnswer(invocation -> {
            BackfillProgressCallback callback = invocation.getArgument(5);
            long segTo = invocation.getArgument(4);
            callback.reportProgress(100, segTo);
            return null;
        }).when(rawFetchSegmentProcessor).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));

        executor.runBackfillForNetwork(
                WALLET,
                NetworkId.ETHEREUM,
                networkAdapter,
                new FixedBlockHeightResolver(100L),
                new NoopTimestampResolver()
        );

        verify(rawFetchSegmentProcessor, times(2)).processSegment(
                eq(WALLET), eq(NetworkId.ETHEREUM), eq(networkAdapter),
                anyLong(), anyLong(), any(BackfillProgressCallback.class));
        verify(rawFetchSegmentProcessor, never()).processSegmentWithBlockCheckpoints(
                anyString(), any(NetworkId.class), any(NetworkAdapter.class),
                anyLong(), anyLong(), anyInt(), any(BackfillProgressCallback.class));
    }

    private void wireSegmentRepository() {
        when(backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(anyString()))
                .thenAnswer(invocation -> {
                    String syncStatusId = invocation.getArgument(0);
                    return segments.values().stream()
                            .filter(s -> syncStatusId.equals(s.getSyncStatusId()))
                            .sorted(Comparator.comparingInt(BackfillSegment::getSegmentIndex))
                            .toList();
                });

        when(backfillSegmentRepository.saveAll(anyCollection()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Collection<BackfillSegment> incoming = invocation.getArgument(0, Collection.class);
                    incoming.forEach(this::putSegment);
                    return incoming;
                });

        when(backfillSegmentRepository.findBySyncStatusIdAndStatusAndUpdatedAtBefore(anyString(), any(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    String syncStatusId = invocation.getArgument(0);
                    BackfillSegment.SegmentStatus status = invocation.getArgument(1);
                    Instant before = invocation.getArgument(2);
                    return segments.values().stream()
                            .filter(s -> syncStatusId.equals(s.getSyncStatusId()))
                            .filter(s -> s.getStatus() == status)
                            .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(before))
                            .sorted(Comparator.comparingInt(BackfillSegment::getSegmentIndex))
                            .toList();
                });

        when(backfillSegmentRepository.findBySyncStatusIdAndStatusInOrderBySegmentIndexAsc(anyString(), anyCollection()))
                .thenAnswer(invocation -> {
                    String syncStatusId = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    Collection<BackfillSegment.SegmentStatus> statuses = invocation.getArgument(1, Collection.class);
                    return segments.values().stream()
                            .filter(s -> syncStatusId.equals(s.getSyncStatusId()))
                            .filter(s -> statuses.contains(s.getStatus()))
                            .sorted(Comparator.comparingInt(BackfillSegment::getSegmentIndex))
                            .toList();
                });

        when(backfillSegmentRepository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(segments.get(invocation.getArgument(0))));

        when(backfillSegmentRepository.save(any(BackfillSegment.class)))
                .thenAnswer(invocation -> {
                    BackfillSegment incoming = invocation.getArgument(0);
                    putSegment(incoming);
                    return incoming;
                });

        when(backfillSegmentRepository.countBySyncStatusId(anyString()))
                .thenAnswer(invocation -> {
                    String syncStatusId = invocation.getArgument(0);
                    return segments.values().stream()
                            .filter(s -> syncStatusId.equals(s.getSyncStatusId()))
                            .count();
                });

        when(backfillSegmentRepository.countBySyncStatusIdAndStatus(anyString(), any()))
                .thenAnswer(invocation -> {
                    String syncStatusId = invocation.getArgument(0);
                    BackfillSegment.SegmentStatus status = invocation.getArgument(1);
                    return segments.values().stream()
                            .filter(s -> syncStatusId.equals(s.getSyncStatusId()))
                            .filter(s -> s.getStatus() == status)
                            .count();
                });
    }

    private void putSegment(BackfillSegment source) {
        if (source.getUpdatedAt() == null) {
            source.setUpdatedAt(Instant.now());
        }
        segments.put(source.getId(), source);
    }

    private List<BackfillSegment> findAllSegments() {
        return segments.values().stream()
                .filter(s -> SYNC_ID.equals(s.getSyncStatusId()))
                .sorted(Comparator.comparingInt(BackfillSegment::getSegmentIndex))
                .toList();
    }

    private static BackfillSegmentsConfiguration segmentProfiles(
            int defaultSegments,
            int defaultWorkers,
            long defaultStaleMs,
            int rpcSegments,
            int rpcWorkers,
            long rpcStaleMs
    ) {
        BackfillSegmentConfiguration defaults = new BackfillSegmentConfiguration(
                defaultStaleMs,
                defaultSegments,
                defaultWorkers
        );
        BackfillSegmentConfiguration byRpc = new BackfillSegmentConfiguration(
                rpcStaleMs,
                rpcSegments,
                rpcWorkers
        );
        BackfillSegmentsConfiguration profiles = new BackfillSegmentsConfiguration();
        profiles.setDefaults(defaults);
        profiles.setByRpc(byRpc);
        return profiles;
    }

    private BackfillSegment segment(int index, long from, long to, BackfillSegment.SegmentStatus status) {
        BackfillSegment s = new BackfillSegment();
        s.setId(SYNC_ID + ":" + index);
        s.setSyncStatusId(SYNC_ID);
        s.setWalletAddress(WALLET);
        s.setNetworkId(NETWORK);
        s.setSegmentIndex(index);
        s.setFromBlock(from);
        s.setToBlock(to);
        s.setStatus(status);
        s.setRetryCount(0);
        s.setProgressPct(0);
        s.setUpdatedAt(Instant.now());
        return s;
    }

    private static class FixedBlockHeightResolver implements BlockHeightResolver {
        private final long currentBlock;

        private FixedBlockHeightResolver(long currentBlock) {
            this.currentBlock = currentBlock;
        }

        @Override
        public boolean supports(NetworkId networkId) {
            return true;
        }

        @Override
        public long getCurrentBlock(NetworkId networkId) {
            return currentBlock;
        }
    }

    private static class NoopTimestampResolver implements BlockTimestampResolver {
        @Override
        public boolean supports(NetworkId networkId) {
            return true;
        }

        @Override
        public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
            return Instant.EPOCH;
        }
    }
}
