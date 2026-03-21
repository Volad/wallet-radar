package com.walletradar.ingestion.store;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentNormalizedTransactionStoreTest {

    @Mock
    private NormalizedTransactionRepository repository;

    @Test
    @DisplayName("reprocessing existing canonical id preserves original createdAt")
    void reprocessingExistingCanonicalIdPreservesCreatedAt() {
        IdempotentNormalizedTransactionStore store = new IdempotentNormalizedTransactionStore(repository);
        Instant originalCreatedAt = Instant.parse("2026-03-19T10:00:00Z");

        NormalizedTransaction existing = normalized("raw-id", originalCreatedAt);
        NormalizedTransaction candidate = normalized("raw-id", Instant.parse("2026-03-19T11:00:00Z"));

        when(repository.findById("raw-id")).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.upsert(candidate);

        ArgumentCaptor<NormalizedTransaction> savedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(repository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getId()).isEqualTo("raw-id");
        assertThat(savedCaptor.getValue().getCreatedAt()).isEqualTo(originalCreatedAt);
    }

    private static NormalizedTransaction normalized(String id, Instant createdAt) {
        NormalizedTransaction normalizedTransaction = new NormalizedTransaction();
        normalizedTransaction.setId(id);
        normalizedTransaction.setTxHash("0xabc");
        normalizedTransaction.setNetworkId(NetworkId.ETHEREUM);
        normalizedTransaction.setWalletAddress("0xwallet");
        normalizedTransaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        normalizedTransaction.setBlockTimestamp(Instant.parse("2026-03-19T09:00:00Z"));
        normalizedTransaction.setTransactionIndex(1);
        normalizedTransaction.setType(NormalizedTransactionType.UNKNOWN);
        normalizedTransaction.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
        normalizedTransaction.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalizedTransaction.setConfidence(ConfidenceLevel.LOW);
        normalizedTransaction.setCreatedAt(createdAt);
        normalizedTransaction.setUpdatedAt(createdAt);
        return normalizedTransaction;
    }
}
