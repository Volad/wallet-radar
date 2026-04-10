package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.transaction.raw.NormalizationStatus;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import com.walletradar.ingestion.filter.ScamFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import org.bson.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RawFetchSegmentProcessorTest {

    @Mock private MongoTemplate mongoTemplate;
    @Mock private BulkOperations bulkOperations;
    @Mock private ScamFilter scamFilter;

    private RawFetchSegmentProcessor processor;

    @BeforeEach
    void setUp() {
        when(scamFilter.shouldDrop(any())).thenReturn(false);
        processor = new RawFetchSegmentProcessor(mongoTemplate, scamFilter);
        when(mongoTemplate.find(any(Query.class), org.mockito.ArgumentMatchers.eq(RawTransaction.class))).thenReturn(List.of());
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RawTransaction.class)).thenReturn(bulkOperations);
        when(bulkOperations.upsert(any(Query.class), any(Update.class))).thenReturn(bulkOperations);
        when(bulkOperations.updateOne(any(Query.class), any(Update.class))).thenReturn(bulkOperations);
    }

    @Test
    @DisplayName("processSegment fetches from adapter and saves each raw tx")
    void processSegment_savesRawTransactions() {
        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                if (from > 50) return List.of();
                RawTransaction tx = new RawTransaction();
                tx.setTxHash("0xabc");
                tx.setNetworkId("ETHEREUM");
                tx.setWalletAddress(wallet);
                tx.setBlockNumber(100L);
                tx.setNormalizationStatus(NormalizationStatus.PENDING);
                return List.of(tx);
            }
        };

        AtomicLong processed = new AtomicLong(0);
        BackfillProgressCallback callback = (pct, lastBlock) -> processed.addAndGet(1);

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 100L, callback);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(bulkOperations, atLeastOnce()).upsert(any(Query.class), updateCaptor.capture());
        org.bson.Document setOnInsertDoc = updateCaptor.getValue().getUpdateObject().get("$setOnInsert", org.bson.Document.class);
        assertThat(setOnInsertDoc.getString("txHash")).isEqualTo("0xabc");
        assertThat(setOnInsertDoc.getString("walletAddress")).isEqualTo("0xWALLET");
        assertThat(setOnInsertDoc.get("blockNumber")).isEqualTo(100L);
        assertThat(setOnInsertDoc.get("normalizationStatus")).isEqualTo(NormalizationStatus.PENDING);
        verify(bulkOperations, atLeastOnce()).execute();
    }

    @Test
    @DisplayName("processSegment with empty adapter returns no saves")
    void processSegment_emptyAdapter_noSaves() {
        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                return List.of();
            }
        };

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 10L, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    @DisplayName("processSegment skips scam tx and does not save")
    void processSegment_scamTx_skipped() {
        when(scamFilter.shouldDrop(any())).thenReturn(true);

        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                RawTransaction tx = new RawTransaction();
                tx.setTxHash("0xscam");
                tx.setNetworkId("ETHEREUM");
                tx.setWalletAddress(wallet);
                tx.setBlockNumber(100L);
                tx.setNormalizationStatus(NormalizationStatus.PENDING);
                return List.of(tx);
            }
        };

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 100L, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    @DisplayName("processSegmentWithBlockCheckpoints reports progress per chunk for RPC resume")
    void processSegmentWithBlockCheckpoints_reportsProgressPerChunk() {
        List<String> ranges = new ArrayList<>();
        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                ranges.add(from + "-" + to);
                return List.of();
            }
        };
        List<Long> checkpoints = new ArrayList<>();

        processor.processSegmentWithBlockCheckpoints(
                "0xWALLET",
                NetworkId.BSC,
                adapter,
                1L,
                10L,
                4,
                (pct, lastBlock) -> checkpoints.add(lastBlock)
        );

        assertThat(ranges).containsExactly("1-4", "5-8", "9-10");
        assertThat(checkpoints).containsExactly(4L, 8L, 10L);
        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    @DisplayName("processSegment keeps existing complete raw rows complete when fetched payload is unchanged")
    void processSegment_unchangedExistingRow_keepsNormalizationStatus() {
        RawTransaction existing = new RawTransaction();
        existing.setId("0xabc:ETHEREUM:0xWALLET");
        existing.setTxHash("0xabc");
        existing.setNetworkId("ETHEREUM");
        existing.setWalletAddress("0xWALLET");
        existing.setBlockNumber(100L);
        existing.setNormalizationStatus(NormalizationStatus.COMPLETE);
        existing.setRawData(new Document("hash", "0xabc"));
        when(mongoTemplate.find(any(Query.class), org.mockito.ArgumentMatchers.eq(RawTransaction.class)))
                .thenReturn(List.of(existing));

        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                RawTransaction tx = new RawTransaction();
                tx.setId("0xabc:ETHEREUM:0xWALLET");
                tx.setTxHash("0xabc");
                tx.setNetworkId("ETHEREUM");
                tx.setWalletAddress(wallet);
                tx.setBlockNumber(100L);
                tx.setNormalizationStatus(NormalizationStatus.PENDING);
                tx.setRawData(new Document("hash", "0xabc"));
                return List.of(tx);
            }
        };

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter, 1L, 10L, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    @DisplayName("processSegment keeps existing raw rows immutable even when fetched payload changed")
    void processSegment_changedExistingRow_skipsMutation() {
        RawTransaction existing = new RawTransaction();
        existing.setId("0xabc:ETHEREUM:0xWALLET");
        existing.setTxHash("0xabc");
        existing.setNetworkId("ETHEREUM");
        existing.setWalletAddress("0xWALLET");
        existing.setBlockNumber(100L);
        existing.setNormalizationStatus(NormalizationStatus.COMPLETE);
        existing.setRawData(new Document("hash", "0xabc"));
        existing.setClarificationEvidence(new Document("old", true));
        when(mongoTemplate.find(any(Query.class), org.mockito.ArgumentMatchers.eq(RawTransaction.class)))
                .thenReturn(List.of(existing));

        NetworkAdapter adapter = new NetworkAdapter() {
            @Override
            public boolean supports(NetworkId networkId) { return true; }
            @Override
            public int getMaxBlockBatchSize() { return 50; }
            @Override
            public List<RawTransaction> fetchTransactions(String wallet, NetworkId network, long from, long to) {
                RawTransaction tx = new RawTransaction();
                tx.setId("0xabc:ETHEREUM:0xWALLET");
                tx.setTxHash("0xabc");
                tx.setNetworkId("ETHEREUM");
                tx.setWalletAddress(wallet);
                tx.setBlockNumber(101L);
                tx.setNormalizationStatus(NormalizationStatus.PENDING);
                tx.setRetryCount(0);
                tx.setRawData(new Document("hash", "0xabc").append("newField", true));
                return List.of(tx);
            }
        };

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter, 1L, 10L, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }
}
