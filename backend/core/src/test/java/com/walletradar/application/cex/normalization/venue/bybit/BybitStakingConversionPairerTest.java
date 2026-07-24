package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle/7 S2 + S7 regression coverage for {@link BybitStakingConversionPairer}. Verifies that
 * orphan ETH-debit + METH-credit singletons are fused into a single STAKING_DEPOSIT with two
 * TRANSFER flows, so that the {@code LiquidStakingReplayHandler} carries basis through the
 * FAMILY:ETH equivalent family.
 */
@ExtendWith(MockitoExtension.class)
class BybitStakingConversionPairerTest {

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    @Test
    void fusesEthDebitAndMethCreditIntoStakingDepositWithTwoFlows() {
        NormalizedTransaction ethDebit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:eth-debit",
                NormalizedTransactionType.INTERNAL_TRANSFER,
                "BYBIT:1:UTA",
                "ETH",
                "-0.6716",
                Instant.parse("2026-03-12T20:08:36Z")
        );
        NormalizedTransaction methCredit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:meth-credit",
                NormalizedTransactionType.STAKING_DEPOSIT,
                "BYBIT:1:UTA",
                "METH",
                "0.6687",
                Instant.parse("2026-03-12T20:08:37Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(ethDebit, methCredit));

        BybitStakingConversionPairer pairer = new BybitStakingConversionPairer(
                mongoOperations,
                normalizedTransactionRepository
        );
        int paired = pairer.pairConversions();

        assertThat(paired).isEqualTo(1);
        verify(normalizedTransactionRepository).saveAll(any());

        // Canonical = ETH-debit (negative-sign leg).
        assertThat(ethDebit.getType()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        // D1 (ADR-054 §9): the fused ETH → mETH pair is cross-canonical (FAMILY:ETH → FAMILY:METH), so
        // it routes to PENDING_PRICE (flag stamped) so both TRANSFER legs enter the pricing chain
        // instead of confirming with an unpriced acquisition leg that strips the ETH family's basis.
        assertThat(ethDebit.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(ethDebit.getCrossCanonicalStakingConversion()).isTrue();
        assertThat(ethDebit.getFlows()).hasSize(2);
        assertThat(ethDebit.getFlows())
                .extracting(NormalizedTransaction.Flow::getAssetSymbol)
                .containsExactlyInAnyOrder("ETH", "METH");
        assertThat(ethDebit.getFlows())
                .allMatch(flow -> flow.getRole() == NormalizedLegRole.TRANSFER);
        assertThat(ethDebit.getCorrelationId()).startsWith("bybit-staking-conv-v1:");
        assertThat(ethDebit.getContinuityCandidate()).isTrue();
        assertThat(ethDebit.getExcludedFromAccounting()).isFalse();

        // Sibling METH-credit doc is demoted.
        assertThat(methCredit.getExcludedFromAccounting()).isTrue();
        assertThat(methCredit.getAccountingExclusionReason()).isEqualTo("BYBIT_STAKING_PAIRED_SIBLING");
        assertThat(methCredit.getCorrelationId()).isEqualTo(ethDebit.getCorrelationId());
    }

    @Test
    void ignoresDocsOutsideFiveMinuteWindow() {
        NormalizedTransaction ethDebit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:eth-debit",
                NormalizedTransactionType.INTERNAL_TRANSFER,
                "BYBIT:1:UTA",
                "ETH",
                "-0.6716",
                Instant.parse("2026-03-12T20:08:36Z")
        );
        NormalizedTransaction methCredit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:meth-credit",
                NormalizedTransactionType.STAKING_DEPOSIT,
                "BYBIT:1:UTA",
                "METH",
                "0.6687",
                // Drift is > 5 minutes — pairer must skip.
                Instant.parse("2026-03-12T20:14:00Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(ethDebit, methCredit));

        int paired = new BybitStakingConversionPairer(
                mongoOperations, normalizedTransactionRepository
        ).pairConversions();

        assertThat(paired).isZero();
        assertThat(ethDebit.getFlows()).hasSize(1);
        assertThat(methCredit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void ignoresSameAssetOppositeSignPairs() {
        // Both legs reference ETH; a real staking conversion has a different asset on the credit
        // side. The pairer must NOT collapse these.
        NormalizedTransaction ethDebit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:eth-debit",
                NormalizedTransactionType.INTERNAL_TRANSFER,
                "BYBIT:1:UTA",
                "ETH",
                "-1.0",
                Instant.parse("2026-03-12T20:08:36Z")
        );
        NormalizedTransaction ethCredit = singleFlowDoc(
                "BYBIT-1:FUNDING_HISTORY:eth-credit",
                NormalizedTransactionType.INTERNAL_TRANSFER,
                "BYBIT:1:UTA",
                "ETH",
                "1.0",
                Instant.parse("2026-03-12T20:08:37Z")
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(ethDebit, ethCredit));

        int paired = new BybitStakingConversionPairer(
                mongoOperations, normalizedTransactionRepository
        ).pairConversions();

        assertThat(paired).isZero();
        assertThat(ethDebit.getFlows()).hasSize(1);
        assertThat(ethCredit.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
    }

    /**
     * RC-9 D1 determinism fix: {@code pairConversions}'s {@code group.sort(...)} now ends in
     * {@code .thenComparing(Candidate::tx, idTiebreak())}. Two same-sign METH credits tie both on
     * {@code blockTimestamp} and on delta-from-debit, so which one the ETH debit fuses with must be
     * a pure function of the candidate set (lowest {@code _id} wins), not of Mongo scan order.
     */
    @Test
    void pairConversionsIsIdempotentAcrossReorderedScan() {
        Instant debitTs = Instant.parse("2026-03-12T20:08:36Z");
        Instant creditTs = debitTs.plusSeconds(180);
        NormalizedTransaction ethDebit = singleFlowDoc(
                "a-eth-debit", NormalizedTransactionType.INTERNAL_TRANSFER, "BYBIT:1:UTA", "ETH", "-1.0", debitTs);
        NormalizedTransaction methCreditLow = singleFlowDoc(
                "b-meth-credit-low", NormalizedTransactionType.STAKING_DEPOSIT, "BYBIT:1:UTA", "METH", "0.5",
                creditTs);
        NormalizedTransaction methCreditHigh = singleFlowDoc(
                "c-meth-credit-high", NormalizedTransactionType.STAKING_DEPOSIT, "BYBIT:1:UTA", "METH", "0.5",
                creditTs);
        List<NormalizedTransaction> docs = List.of(ethDebit, methCreditLow, methCreditHigh);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(methCreditHigh, ethDebit, methCreditLow))
                .thenReturn(List.of(methCreditLow, methCreditHigh, ethDebit));

        BybitStakingConversionPairer pairer =
                new BybitStakingConversionPairer(mongoOperations, normalizedTransactionRepository);

        BybitDeterminismTestSupport.assertReorderInvariant(docs, pairer::pairConversions, pairer::pairConversions);

        assertThat(ethDebit.getType()).isEqualTo(NormalizedTransactionType.STAKING_DEPOSIT);
        assertThat(ethDebit.getCorrelationId()).isEqualTo(methCreditLow.getCorrelationId());
        assertThat(methCreditHigh.getExcludedFromAccounting()).isNotEqualTo(Boolean.TRUE);
        assertThat(methCreditHigh.getCorrelationId()).isNull();
    }

    private NormalizedTransaction singleFlowDoc(
            String id,
            NormalizedTransactionType type,
            String walletAddress,
            String assetSymbol,
            String quantity,
            Instant timestamp
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(type);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(walletAddress);
        tx.setBlockTimestamp(timestamp);
        tx.setExcludedFromAccounting(false);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(flow);
        tx.setFlows(flows);
        return tx;
    }
}
