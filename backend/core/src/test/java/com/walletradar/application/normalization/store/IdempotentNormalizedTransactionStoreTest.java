package com.walletradar.application.normalization.store;

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

import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentNormalizedTransactionStoreTest {

    @Mock
    private NormalizedTransactionRepository repository;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<NormalizedTransactionPostProcessor> emptyProviders() {
        var mock = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(mock.stream()).thenReturn(Stream.empty());
        return mock;
    }

    @Test
    @DisplayName("reprocessing existing canonical id preserves original createdAt")
    void reprocessingExistingCanonicalIdPreservesCreatedAt() {
        IdempotentNormalizedTransactionStore store = new IdempotentNormalizedTransactionStore(repository, emptyProviders());
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

    @Test
    @DisplayName("confirmed merge preserves higher clarification counters from candidate")
    void confirmedMergePreservesHigherClarificationCountersFromCandidate() {
        IdempotentNormalizedTransactionStore store = new IdempotentNormalizedTransactionStore(repository, emptyProviders());
        Instant originalCreatedAt = Instant.parse("2026-03-19T10:00:00Z");

        NormalizedTransaction existing = normalized("raw-id", originalCreatedAt);
        existing.setStatus(NormalizedTransactionStatus.CONFIRMED);
        existing.setClarificationAttempts(0);
        existing.setFullReceiptClarificationAttempts(0);

        NormalizedTransaction candidate = normalized("raw-id", Instant.parse("2026-03-19T11:00:00Z"));
        candidate.setStatus(NormalizedTransactionStatus.CONFIRMED);
        candidate.setClarificationAttempts(1);
        candidate.setFullReceiptClarificationAttempts(1);

        when(repository.findById("raw-id")).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        store.upsert(candidate);

        ArgumentCaptor<NormalizedTransaction> savedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(repository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getClarificationAttempts()).isEqualTo(1);
        assertThat(savedCaptor.getValue().getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-051: confirmed merge propagates acquisitionFeeUsd from candidate BUY flow")
    void confirmedMergePropagateskAcquisitionFeeUsdOnBuyFlow() {
        IdempotentNormalizedTransactionStore store = new IdempotentNormalizedTransactionStore(repository, emptyProviders());

        NormalizedTransaction existing = normalized("dz-tsla-1", Instant.parse("2026-03-19T10:00:00Z"));
        existing.setStatus(NormalizedTransactionStatus.CONFIRMED);
        NormalizedTransaction.Flow existingBuyFlow = new NormalizedTransaction.Flow();
        existingBuyFlow.setRole(com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY);
        existingBuyFlow.setAssetSymbol("TSLA");
        existingBuyFlow.setQuantityDelta(java.math.BigDecimal.ONE);
        existing.setFlows(new java.util.ArrayList<>(java.util.List.of(existingBuyFlow)));

        NormalizedTransaction candidate = normalized("dz-tsla-1", Instant.parse("2026-03-19T10:00:00Z"));
        NormalizedTransaction.Flow candidateBuyFlow = new NormalizedTransaction.Flow();
        candidateBuyFlow.setRole(com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY);
        candidateBuyFlow.setAssetSymbol("TSLA");
        candidateBuyFlow.setQuantityDelta(java.math.BigDecimal.ONE);
        candidateBuyFlow.setAcquisitionFeeUsd(new java.math.BigDecimal("0.228"));
        candidate.setFlows(java.util.List.of(candidateBuyFlow));

        when(repository.findById("dz-tsla-1")).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        store.upsert(candidate);

        ArgumentCaptor<NormalizedTransaction> savedCaptor = ArgumentCaptor.forClass(NormalizedTransaction.class);
        verify(repository).save(savedCaptor.capture());
        NormalizedTransaction.Flow savedBuy = savedCaptor.getValue().getFlows().stream()
                .filter(f -> f.getRole() == com.walletradar.domain.transaction.normalized.NormalizedLegRole.BUY)
                .findFirst().orElseThrow();
        assertThat(savedBuy.getAcquisitionFeeUsd())
                .as("acquisitionFeeUsd must be propagated to CONFIRMED transaction's BUY flow")
                .isEqualByComparingTo("0.228");
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
