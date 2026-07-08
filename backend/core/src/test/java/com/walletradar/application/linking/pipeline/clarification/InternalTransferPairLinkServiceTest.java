package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SessionWalletAdjacencyService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransferPairLinkServiceTest {

    private static final String WALLET_A = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String WALLET_B = "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private SessionWalletAdjacencyService sessionWalletAdjacencyService;

    private InternalTransferPairLinkService service;

    @BeforeEach
    void setUp() {
        service = new InternalTransferPairLinkService(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService,
                sessionWalletAdjacencyService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountingUniverseService.shareUniverseMembers(WALLET_A, WALLET_B)).thenReturn(true);
        lenient().when(accountingUniverseService.shareUniverseMembers(WALLET_B, WALLET_A)).thenReturn(true);
        lenient().when(sessionWalletAdjacencyService.anySessionListsBothAddresses(any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("reciprocal same-tx on-chain pair is promoted into internal transfer")
    void reciprocalSameTxPairIsPromotedIntoInternalTransfer() {
        NormalizedTransaction outbound = transfer(
                WALLET_A,
                WALLET_B,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                "-0.0024"
        );
        NormalizedTransaction inbound = transfer(
                WALLET_B,
                WALLET_A,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                "0.0024"
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound));
        when(normalizedTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                outbound.getTxHash(),
                outbound.getNetworkId(),
                outbound.getMatchedCounterparty()
        )).thenReturn(Optional.of(inbound));

        int changed = service.reconcileOutstandingPairs(10);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<List<NormalizedTransaction>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(normalizedTransactionRepository).saveAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).extracting(NormalizedTransaction::getTxHash)
                .containsExactlyInAnyOrder(outbound.getTxHash(), inbound.getTxHash());
        assertThat(outbound.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(inbound.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(outbound.getFlows()).allSatisfy(flow -> {
            if (flow.getRole() != NormalizedLegRole.FEE) {
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                assertThat(flow.getUnitPriceUsd()).isNull();
                assertThat(flow.getPriceSource()).isNull();
            }
        });
        assertThat(inbound.getFlows()).allSatisfy(flow -> {
            if (flow.getRole() != NormalizedLegRole.FEE) {
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
                assertThat(flow.getUnitPriceUsd()).isNull();
                assertThat(flow.getPriceSource()).isNull();
            }
        });
    }

    @Test
    @DisplayName("one-sided tracked-counterparty row does not auto-promote without reciprocal peer")
    void oneSidedTrackedCounterpartyRowDoesNotAutoPromoteWithoutReciprocalPeer() {
        NormalizedTransaction inbound = transfer(
                WALLET_A,
                WALLET_B,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                "0.000037822242356935"
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound));
        when(normalizedTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                inbound.getTxHash(),
                inbound.getNetworkId(),
                inbound.getMatchedCounterparty()
        )).thenReturn(Optional.empty());

        int changed = service.reconcileOutstandingPairs(10);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(inbound.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(inbound.getFlows()).extracting(NormalizedTransaction.Flow::getRole)
                .containsExactly(NormalizedLegRole.BUY);
    }

    @Test
    @DisplayName("reciprocal pair is not promoted across different accounting universes")
    void reciprocalPairIsNotPromotedAcrossDifferentAccountingUniverses() {
        NormalizedTransaction outbound = transfer(
                WALLET_A,
                WALLET_B,
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT,
                "-0.0024"
        );
        NormalizedTransaction inbound = transfer(
                WALLET_B,
                WALLET_A,
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                "0.0024"
        );

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(outbound));
        when(normalizedTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                outbound.getTxHash(),
                outbound.getNetworkId(),
                outbound.getMatchedCounterparty()
        )).thenReturn(Optional.of(inbound));
        when(accountingUniverseService.shareUniverseMembers(WALLET_A, WALLET_B)).thenReturn(false);

        int changed = service.reconcileOutstandingPairs(10);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
        assertThat(outbound.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        assertThat(inbound.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    private NormalizedTransaction transfer(
            String walletAddress,
            String matchedCounterparty,
            NormalizedTransactionType type,
            String quantity
    ) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("0xhash:" + walletAddress);
        transaction.setTxHash("0x1a2657ac7b825dfeba3ce36641ad1df3e4a2d1e20520492102e36f195c5bc8af");
        transaction.setWalletAddress(walletAddress);
        transaction.setMatchedCounterparty(matchedCounterparty);
        transaction.setContinuityCandidate(true);
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setNetworkId(NetworkId.ETHEREUM);
        transaction.setType(type);
        transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
        transaction.setBlockTimestamp(Instant.parse("2025-03-12T10:00:00Z"));
        transaction.setTransactionIndex(1);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(quantity.startsWith("-") ? NormalizedLegRole.SELL : NormalizedLegRole.BUY);
        transaction.setFlows(List.of(flow));
        return transaction;
    }
}
