package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.domain.SyncStatus;
import com.walletradar.domain.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockTimestampResolver;
import com.walletradar.ingestion.config.ClassifierProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.pipeline.classification.ClassificationProcessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RawTransactionClassifierJobTest {

    @Mock private RawTransactionRepository rawTransactionRepository;
    @Mock private SyncStatusRepository syncStatusRepository;
    @Mock private ClassificationProcessor classificationProcessor;
    @Mock private ClassifierProperties classifierProperties;
    @Mock private IngestionNetworkProperties ingestionNetworkProperties;

    private RawTransactionClassifierJob job;

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
        job = new RawTransactionClassifierJob(
                rawTransactionRepository,
                syncStatusRepository,
                classificationProcessor,
                classifierProperties,
                ingestionNetworkProperties,
                List.of(stubResolver)
        );
        lenient().when(classifierProperties.getBatchSize()).thenReturn(1000);
    }

    @Test
    @DisplayName("does nothing when no sync_status with rawFetchComplete")
    void noWorkWhenNoRawFetchComplete() {
        when(syncStatusRepository.findAll()).thenReturn(List.of());

        job.runClassification();

        verify(rawTransactionRepository, never()).findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(anyString(), anyString(), any(), any(PageRequest.class));
    }

    @Test
    @DisplayName("processes PENDING raw and calls processBatch")
    void processesPendingAndCallsProcessBatch() {
        SyncStatus syncStatus = new SyncStatus();
        syncStatus.setWalletAddress("0xWALLET");
        syncStatus.setNetworkId("ETHEREUM");
        syncStatus.setRawFetchComplete(true);
        when(syncStatusRepository.findAll()).thenReturn(List.of(syncStatus));

        RawTransaction raw = new RawTransaction();
        raw.setTxHash("0xabc");
        raw.setWalletAddress("0xWALLET");
        raw.setNetworkId("ETHEREUM");
        raw.setBlockNumber(100L);
        raw.setClassificationStatus(ClassificationStatus.PENDING);
        when(rawTransactionRepository.findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(
                eq("0xWALLET"), eq("ETHEREUM"), eq(ClassificationStatus.PENDING), any(PageRequest.class)))
                .thenReturn(List.of(raw));

        var networkEntry = new IngestionNetworkProperties.NetworkIngestionEntry();
        networkEntry.setAvgBlockTimeSeconds(12.0);
        when(ingestionNetworkProperties.getNetwork()).thenReturn(Map.of("ETHEREUM", networkEntry));

        job.runClassification();

        verify(classificationProcessor).processBatch(
                eq(List.of(raw)),
                eq("0xWALLET"),
                eq(NetworkId.ETHEREUM),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("skips when PENDING list is empty for wallet×network")
    void skipsWhenNoPending() {
        SyncStatus syncStatus = new SyncStatus();
        syncStatus.setWalletAddress("0xWALLET");
        syncStatus.setNetworkId("ETHEREUM");
        syncStatus.setRawFetchComplete(true);
        when(syncStatusRepository.findAll()).thenReturn(List.of(syncStatus));
        when(rawTransactionRepository.findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(
                anyString(), anyString(), eq(ClassificationStatus.PENDING), any(PageRequest.class)))
                .thenReturn(List.of());

        job.runClassification();

        verify(classificationProcessor, never()).processBatch(any(), anyString(), any(), any(), any(), any(), any());
    }
}
