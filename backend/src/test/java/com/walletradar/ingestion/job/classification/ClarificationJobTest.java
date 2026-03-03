package com.walletradar.ingestion.job.classification;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.adapter.TransactionClarificationResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClarificationJobTest {

    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private TransactionClarificationResolver clarificationResolver;

    private ClarificationJob clarificationJob;

    @BeforeEach
    void setUp() {
        clarificationJob = new ClarificationJob(normalizedTransactionRepository, List.of(clarificationResolver));
        ReflectionTestUtils.setField(clarificationJob, "maxRetries", 2);
    }

    @Test
    @DisplayName("successful clarification adds inferred leg and moves to PENDING_PRICE")
    void successfulClarificationMovesToPendingPrice() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        when(clarificationResolver.supports(NetworkId.ARBITRUM)).thenReturn(true);

        NormalizedTransaction.Flow inferred = new NormalizedTransaction.Flow();
        inferred.setRole(NormalizedLegRole.BUY);
        inferred.setAssetContract("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        inferred.setAssetSymbol("ETH");
        inferred.setQuantityDelta(new BigDecimal("0.75"));

        when(clarificationResolver.clarify(tx)).thenReturn(Optional.of(
                new TransactionClarificationResolver.ClarificationResult(
                        List.of(inferred),
                        "INFERRED_FROM_TRACE",
                        ConfidenceLevel.MEDIUM)));

        clarificationJob.clarifyOne(tx);

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(tx.getFlows()).hasSize(2);
        assertThat(tx.getFlows().get(1).isInferred()).isTrue();
        assertThat(tx.getFlows().get(1).getInferenceReason()).isEqualTo("INFERRED_FROM_TRACE");
        assertThat(tx.getFlows().get(1).getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        verify(normalizedTransactionRepository).save(tx);
    }

    @Test
    @DisplayName("unresolved clarification reaches NEEDS_REVIEW after retries")
    void unresolvedClarificationNeedsReviewAfterRetries() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        tx.setClarificationAttempts(1);

        when(clarificationResolver.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(clarificationResolver.clarify(tx)).thenReturn(Optional.empty());

        clarificationJob.clarifyOne(tx);

        assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.NEEDS_REVIEW);
        assertThat(tx.getMissingDataReasons()).contains("CLARIFICATION_UNRESOLVED");
        verify(normalizedTransactionRepository).save(tx);
    }

    @Test
    @DisplayName("runScheduled processes only PENDING_CLARIFICATION items")
    void runScheduledSelectiveForPendingClarificationOnly() {
        NormalizedTransaction tx = pendingSwapWithoutBuy();
        when(normalizedTransactionRepository.findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_CLARIFICATION))
                .thenReturn(List.of(tx));
        when(clarificationResolver.supports(NetworkId.ARBITRUM)).thenReturn(true);
        when(clarificationResolver.clarify(any())).thenReturn(Optional.empty());

        clarificationJob.runScheduled();

        verify(normalizedTransactionRepository).findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        verify(clarificationResolver, times(1)).clarify(tx);
        verify(normalizedTransactionRepository, times(1)).save(tx);
    }

    private static NormalizedTransaction pendingSwapWithoutBuy() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash("0xswap");
        tx.setWalletAddress("0xwallet");
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setType(NormalizedTransactionType.SWAP);
        tx.setStatus(NormalizedTransactionStatus.PENDING_CLARIFICATION);
        tx.setMissingDataReasons(List.of("MISSING_SWAP_LEG"));
        tx.setClarificationAttempts(0);

        NormalizedTransaction.Flow sell = new NormalizedTransaction.Flow();
        sell.setRole(NormalizedLegRole.SELL);
        sell.setAssetContract("0xwstusr");
        sell.setAssetSymbol("wstUSR");
        sell.setQuantityDelta(new BigDecimal("-100"));
        tx.setFlows(List.of(sell));
        return tx;
    }
}
