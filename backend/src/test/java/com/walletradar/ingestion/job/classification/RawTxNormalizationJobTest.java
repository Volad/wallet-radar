package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.domain.transaction.raw.RawTransactionRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawTxNormalizationJobTest {

    @Mock private RawTransactionRepository rawTransactionRepository;
    @Mock private ClassificationProcessor classificationProcessor;
    @Mock private ClassifierProperties classifierProperties;
    @Mock private IngestionNetworkProperties ingestionNetworkProperties;

    private RawTxNormalizationJob job;

    @BeforeEach
    void setUp() {
        BlockTimestampResolver stubResolver = new BlockTimestampResolver() {
            @Override
            public boolean supports(NetworkId networkId) { return networkId == NetworkId.ETHEREUM; }
            @Override
            public Instant getBlockTimestamp(NetworkId networkId, long blockNumber) {
                return Instant.ofEpochSecond(1_700_000_000L + blockNumber * 12L);
            }
        };
        job = new RawTxNormalizationJob(
                rawTransactionRepository,
                classificationProcessor,
                classifierProperties,
                ingestionNetworkProperties,
                List.of(stubResolver)
        );
        lenient().when(classifierProperties.getBatchSize()).thenReturn(1000);
    }

    @Test
    @DisplayName("does nothing when no pending raw transactions")
    void noWorkWhenNoPendingRawTransactions() {
        when(rawTransactionRepository.findByNormalizationStatusAndNetworkIdNot(
                eq(NormalizationStatus.PENDING), eq("SOLANA"), any()))
                .thenReturn(List.of());

        job.runNormalization();

        verify(classificationProcessor, never()).processBatch(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("processes PENDING raw and calls processBatch")
    void processesPendingAndCallsProcessBatch() {
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc");
        raw.setWalletAddress("0xWALLET");
        raw.setNetworkId("ETHEREUM");
        raw.setBlockNumber(100L);
        raw.setNormalizationStatus(NormalizationStatus.PENDING);
        when(rawTransactionRepository.findByNormalizationStatusAndNetworkIdNot(
                eq(NormalizationStatus.PENDING), eq("SOLANA"), any()))
                .thenReturn(List.of(raw), List.of());

        var networkEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        networkEntry.setAvgBlockTimeSeconds(12.0);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of("ETHEREUM", networkEntry));

        job.runNormalization();

        verify(classificationProcessor).processBatch(
                eq(List.of(raw)),
                eq("0xWALLET"),
                eq(NetworkId.ETHEREUM),
                any()
        );
    }

    @Test
    @DisplayName("skips when PENDING list is empty for wallet×network")
    void skipsWhenNoPending() {
        when(rawTransactionRepository.findByNormalizationStatusAndNetworkIdNot(
                eq(NormalizationStatus.PENDING), eq("SOLANA"), any()))
                .thenReturn(List.of());

        job.runNormalization();

        verify(classificationProcessor, never()).processBatch(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("skips concurrent run when previous normalization is still in progress")
    void skipsConcurrentRun() throws Exception {
        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc");
        raw.setWalletAddress("0xWALLET");
        raw.setNetworkId("ETHEREUM");
        raw.setBlockNumber(100L);
        raw.setNormalizationStatus(NormalizationStatus.PENDING);

        var networkEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        networkEntry.setAvgBlockTimeSeconds(12.0);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of("ETHEREUM", networkEntry));

        AtomicInteger repoCalls = new AtomicInteger(0);
        when(rawTransactionRepository.findByNormalizationStatusAndNetworkIdNot(
                eq(NormalizationStatus.PENDING), eq("SOLANA"), any()))
                .thenAnswer((Answer<List<RawTransaction>>) invocation ->
                        repoCalls.getAndIncrement() == 0 ? List.of(raw) : List.of());

        CountDownLatch inProcess = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inProcess.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(classificationProcessor).processBatch(any(), anyString(), any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> job.runNormalization());
            org.junit.jupiter.api.Assertions.assertTrue(inProcess.await(2, TimeUnit.SECONDS));

            job.runNormalization();

            release.countDown();
            future.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(classificationProcessor, times(1)).processBatch(any(), anyString(), any(), any());
        verify(rawTransactionRepository, times(2))
                .findByNormalizationStatusAndNetworkIdNot(eq(NormalizationStatus.PENDING), eq("SOLANA"), any());
    }
}
