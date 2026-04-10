package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BybitTransferContinuityRepairServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String BYBIT = "BYBIT:33625378";
    private static final String TX_HASH = "0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;

    private BybitTransferContinuityRepairService service;

    @BeforeEach
    void setUp() {
        service = new BybitTransferContinuityRepairService(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountingUniverseService.shareUniverseMembers(WALLET, BYBIT)).thenReturn(true);
        lenient().when(accountingUniverseService.shareUniverseMembers(BYBIT, WALLET)).thenReturn(true);
    }

    @Test
    @DisplayName("matched Bybit deposit repairs missing on-chain continuity metadata after rerun")
    void matchedBybitDepositRepairsMissingOnChainContinuityMetadataAfterRerun() {
        NormalizedTransaction onChain = onChainRow();
        NormalizedTransaction bybit = bybitRow();

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getWalletAddress)
                .containsExactlyInAnyOrder(WALLET, BYBIT);

        assertThat(onChain.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(onChain.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(onChain.getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:" + TX_HASH);
        assertThat(onChain.getContinuityCandidate()).isTrue();
        assertThat(onChain.getMatchedCounterparty()).isEqualTo(BYBIT);

        assertThat(bybit.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(bybit.getCorrelationId()).isEqualTo("BYBIT:ARBITRUM:" + TX_HASH);
        assertThat(bybit.getContinuityCandidate()).isTrue();
        assertThat(bybit.getMatchedCounterparty()).isEqualTo(WALLET);
    }

    @Test
    @DisplayName("one-wei dust transfer without Bybit row is ignored")
    void oneWeiDustTransferWithoutBybitRowIsIgnored() {
        NormalizedTransaction onChain = onChainRow();
        onChain.setId("on-chain-dust");
        onChain.setTxHash("0xcce37c54e31867ed9bca2f34ffbfdbb62329811896b9e60f2ae087a4d2b41e67");
        onChain.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        onChain.getFlows().getFirst().setQuantityDelta(new BigDecimal("0.000000000000000001"));
        onChain.getFlows().getFirst().setRole(NormalizedLegRole.BUY);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                onChain.getTxHash(),
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of());

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(onChain.getCorrelationId()).isNull();
        assertThat(onChain.getMatchedCounterparty()).isNull();
    }

    @Test
    @DisplayName("wallet to Bybit repair is skipped across different accounting universes")
    void walletToBybitRepairIsSkippedAcrossDifferentAccountingUniverses() {
        NormalizedTransaction onChain = onChainRow();
        NormalizedTransaction bybit = bybitRow();

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(onChain));
        when(normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                TX_HASH,
                NetworkId.ARBITRUM,
                NormalizedTransactionSource.BYBIT
        )).thenReturn(List.of(bybit));
        when(accountingUniverseService.shareUniverseMembers(WALLET, BYBIT)).thenReturn(false);

        int changed = service.reconcileOutstandingPairs(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(onChain.getCorrelationId()).isNull();
        assertThat(onChain.getMatchedCounterparty()).isNull();
    }

    private NormalizedTransaction onChainRow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("on-chain");
        transaction.setTxHash(TX_HASH);
        transaction.setWalletAddress(WALLET);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        transaction.setTransactionIndex(1);
        transaction.setFlows(List.of(flow("-3.06", NormalizedLegRole.SELL)));
        return transaction;
    }

    private NormalizedTransaction bybitRow() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("bybit");
        transaction.setTxHash(TX_HASH);
        transaction.setWalletAddress(BYBIT);
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setNetworkId(NetworkId.ARBITRUM);
        transaction.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setCorrelationId("BYBIT:ARBITRUM:" + TX_HASH);
        transaction.setContinuityCandidate(true);
        transaction.setMatchedCounterparty(WALLET);
        transaction.setBlockTimestamp(Instant.parse("2026-03-12T10:00:01Z"));
        transaction.setTransactionIndex(0);
        transaction.setFlows(List.of(flow("3.06", NormalizedLegRole.BUY)));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(String quantity, NormalizedLegRole role) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(role);
        return flow;
    }
}
