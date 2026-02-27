package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
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
        when(scamFilter.isScam(any())).thenReturn(false);
        processor = new RawFetchSegmentProcessor(mongoTemplate, scamFilter);
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, RawTransaction.class)).thenReturn(bulkOperations);
        when(bulkOperations.upsert(any(Query.class), any(Update.class))).thenReturn(bulkOperations);
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
                tx.setClassificationStatus(ClassificationStatus.PENDING);
                return List.of(tx);
            }
        };

        AtomicLong processed = new AtomicLong(0);
        BackfillProgressCallback callback = (pct, lastBlock) -> processed.addAndGet(1);

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 100L, 50, callback);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(bulkOperations, atLeastOnce()).upsert(any(Query.class), updateCaptor.capture());
        org.bson.Document setDoc = updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(setDoc.getString("txHash")).isEqualTo("0xabc");
        assertThat(setDoc.getString("walletAddress")).isEqualTo("0xWALLET");
        assertThat(setDoc.get("blockNumber")).isEqualTo(100L);
        assertThat(setDoc.get("classificationStatus")).isEqualTo(ClassificationStatus.PENDING);
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
                1L, 10L, 50, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }

    @Test
    @DisplayName("processSegment skips scam tx and does not save")
    void processSegment_scamTx_skipped() {
        when(scamFilter.isScam(any())).thenReturn(true);

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
                tx.setClassificationStatus(ClassificationStatus.PENDING);
                return List.of(tx);
            }
        };

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 100L, 50, (pct, lastBlock) -> {});

        verify(mongoTemplate, never()).bulkOps(any(), any(Class.class));
    }
}
