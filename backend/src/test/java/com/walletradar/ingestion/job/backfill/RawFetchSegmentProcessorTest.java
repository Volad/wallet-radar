package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.ClassificationStatus;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.RawTransaction;
import com.walletradar.domain.RawTransactionRepository;
import com.walletradar.ingestion.adapter.NetworkAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RawFetchSegmentProcessorTest {

    @Mock private RawTransactionRepository rawTransactionRepository;

    private RawFetchSegmentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RawFetchSegmentProcessor(rawTransactionRepository);
        when(rawTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        BackfillProgressCallback callback = (pct, lastBlock, msg) -> processed.addAndGet(1);

        processor.processSegment("0xWALLET", NetworkId.ETHEREUM, adapter,
                1L, 100L, 50, processed, 100L, callback);

        ArgumentCaptor<RawTransaction> captor = ArgumentCaptor.forClass(RawTransaction.class);
        verify(rawTransactionRepository, atLeastOnce()).save(captor.capture());
        RawTransaction saved = captor.getValue();
        assertThat(saved.getTxHash()).isEqualTo("0xabc");
        assertThat(saved.getWalletAddress()).isEqualTo("0xWALLET");
        assertThat(saved.getBlockNumber()).isEqualTo(100L);
        assertThat(saved.getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
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
                1L, 10L, 50, new AtomicLong(0), 10L, (pct, lastBlock, msg) -> {});

        verify(rawTransactionRepository, never()).save(any());
    }
}
