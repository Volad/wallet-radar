package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle/6 A3 regression coverage for {@link BybitInternalTransferExternalCpReclassifier}. When the
 * counterparty address on a Bybit INTERNAL_TRANSFER row is neither a Bybit sub-account nor a member
 * of the universe, the row must be reclassified as an external transfer (IN/OUT depending on the
 * principal flow sign) so the conservation gate accounts for it as deposit / withdraw.
 */
@ExtendWith(MockitoExtension.class)
class BybitInternalTransferExternalCpReclassifierTest {

    private static final String UNIVERSE_ID = "universe-1";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    @Test
    void reclassifiesPositiveFlowAsExternalTransferIn() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT-1:INTERNAL_TRANSFER:ext-in",
                "BYBIT:42:FUND",
                "uqdcaquhb07rh_df1iy56ef5wue_xgmq4khnlps-m7v9o37o",
                "TON",
                "10.5"
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));
        when(accountingUniverseService.classify(eq(UNIVERSE_ID), any(String.class), any()))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassify(UNIVERSE_ID);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        List<NormalizedTransaction> dirty = new ArrayList<>();
        captor.getValue().forEach(dirty::add);
        assertThat(dirty).hasSize(1);
        NormalizedTransaction saved = dirty.get(0);
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(saved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(saved.getCorrelationId()).isNull();
        assertThat(saved.getMatchedCounterparty()).isNull();
        assertThat(saved.getContinuityCandidate()).isFalse();
        // Cycle/8 S1: reclassified doc must re-enter pricing pipeline so the BUY flow gets a
        // historical USD quote (otherwise basis = $0).
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getConfirmedAt()).isNull();
    }

    @Test
    void reclassifiesNegativeFlowAsExternalTransferOut() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT-1:INTERNAL_TRANSFER:ext-out",
                "BYBIT:42:FUND",
                "uqaeuho4bzdfmceqiyyuuh8ujmrsgjowe2124obdmvbs1ms",
                "TON",
                "-5.0"
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));
        when(accountingUniverseService.classify(eq(UNIVERSE_ID), any(String.class), any()))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassify(UNIVERSE_ID);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        NormalizedTransaction saved = captor.getValue().iterator().next();
        assertThat(saved.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(saved.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.SELL);
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
    }

    @Test
    void resetsConfirmedStatusBackToPendingPriceAfterReclassification() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT-1:INTERNAL_TRANSFER:confirmed-orphan",
                "BYBIT:42:FUND",
                "uqdcaquhb07rh_df1iy56ef5wue_xgmq4khnlps-m7v9o37o",
                "DOGE",
                "150.591"
        );
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setConfirmedAt(java.time.Instant.parse("2025-02-21T20:43:39Z"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));
        when(accountingUniverseService.classify(eq(UNIVERSE_ID), any(String.class), any()))
                .thenReturn(new AccountingUniverseService.OwnMembership(false, null, false, null));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassify(UNIVERSE_ID);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<Iterable<NormalizedTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(normalizedTransactionRepository).saveAll(captor.capture());
        NormalizedTransaction saved = captor.getValue().iterator().next();
        assertThat(saved.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        assertThat(saved.getConfirmedAt()).isNull();
    }

    @Test
    void leavesBybitSubAccountCounterpartyUntouched() {
        NormalizedTransaction tx = internalTransfer(
                "BYBIT-1:INTERNAL_TRANSFER:internal",
                "BYBIT:42:FUND",
                "BYBIT:42:UTA",
                "USDT",
                "100"
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassify(UNIVERSE_ID);

        assertThat(count).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("same-uid FUND↔UTA EXTERNAL_TRANSFER_IN is demoted to INTERNAL_TRANSFER")
    void sameUidEvenSubAccountFlipsExternalToInternal() {
        NormalizedTransaction tx = externalTransfer(
                "BYBIT-33625378:INTERNAL_TRANSFER:selfTransfer_test",
                "BYBIT:33625378:UTA",
                "BYBIT:33625378:FUND",
                "ETH",
                "3.06",
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassifySameUidExternalToInternal(Instant.now());

        assertThat(count).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("earn-principal corrId is preserved when demoting same-uid external to internal")
    void earnPrincipalCorrIdSurvivesSameUidDemotion() {
        // Defense-in-depth (ADR-029 D1): if a paired earn-principal FUND leg ever lands here as an
        // EXTERNAL_TRANSFER_OUT, demoting it back to INTERNAL_TRANSFER must NOT null its
        // bybit-earn-principal-v1: corrId — otherwise BybitOnChainEarnOrphanRepairService would treat
        // it as a blank-corr orphan and re-link it forever (linking convergence loop).
        String earnPrincipalCorr =
                BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX + "deadbeef";
        NormalizedTransaction tx = externalTransfer(
                "BYBIT-33625378:INTERNAL_TRANSFER:earnPrincipalLeg",
                "BYBIT:33625378:FUND",
                "BYBIT:33625378:EARN",
                "MNT",
                "263.6026",
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
        );
        tx.setCorrelationId(earnPrincipalCorr);
        tx.setContinuityCandidate(true);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassifySameUidExternalToInternal(Instant.now());

        assertThat(count).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        // corrId and continuity flag survive so replay still pairs the earn-principal legs.
        assertThat(tx.getCorrelationId()).isEqualTo(earnPrincipalCorr);
        assertThat(tx.getContinuityCandidate()).isTrue();
    }

    @Test
    @DisplayName("different Bybit uid stays EXTERNAL_TRANSFER")
    void differentUidLeavesExternalUntouched() {
        NormalizedTransaction tx = externalTransfer(
                "BYBIT-999:INTERNAL_TRANSFER:other",
                "BYBIT:33625378:FUND",
                "BYBIT:999:UTA",
                "USDT",
                "10",
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN
        );
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(tx));

        BybitInternalTransferExternalCpReclassifier reclassifier = new BybitInternalTransferExternalCpReclassifier(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        int count = reclassifier.reclassifySameUidExternalToInternal(Instant.now());

        assertThat(count).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    private NormalizedTransaction externalTransfer(
            String id,
            String walletRef,
            String counterparty,
            String asset,
            String qty,
            NormalizedTransactionType type
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(type);
        tx.setWalletAddress(walletRef);
        tx.setCounterpartyAddress(counterparty);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                ? NormalizedLegRole.BUY
                : NormalizedLegRole.SELL);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setUnitPriceUsd(new BigDecimal("2000"));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction internalTransfer(
            String id,
            String walletRef,
            String counterparty,
            String asset,
            String qty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(walletRef);
        tx.setCounterpartyAddress(counterparty);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("seed-corr");
        tx.setMatchedCounterparty(counterparty);
        tx.setNetworkId(NetworkId.TON);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
