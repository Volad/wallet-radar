package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionWalletAdjacencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnChainInternalTransferPairRepairServiceTest {

    private static final String TX = "0x96cd19c322a3f12905480f33ed5a04891d6ebe78da23b1c036dbe9f31f72c00e";
    private static final String WALLET_A = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
    private static final String WALLET_B = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private SessionWalletAdjacencyService sessionWalletAdjacencyService;

    private OnChainInternalTransferPairRepairService service;

    @BeforeEach
    void setUp() {
        service = new OnChainInternalTransferPairRepairService(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService,
                sessionWalletAdjacencyService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountingUniverseService.shareUniverseMembers(WALLET_A, WALLET_B)).thenReturn(true);
    }

    @Test
    void pairsSameTxReciprocalInternalTransfers() {
        NormalizedTransaction inbound = internal(WALLET_A, "10", NormalizedLegRole.BUY);
        NormalizedTransaction outbound = internal(WALLET_B, "-10", NormalizedLegRole.SELL);

        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(inbound))
                .thenReturn(List.of(outbound));

        int repaired = service.reconcileOrphanSameTxPairs(20);

        assertThat(repaired).isEqualTo(1);
        assertThat(inbound.getContinuityCandidate()).isTrue();
        assertThat(outbound.getContinuityCandidate()).isTrue();
        assertThat(inbound.getMatchedCounterparty()).isEqualTo(WALLET_B);
        assertThat(outbound.getMatchedCounterparty()).isEqualTo(WALLET_A);
        assertThat(inbound.getCorrelationId()).startsWith("internal-tx:");
        assertThat(inbound.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(inbound.getStatus()).isEqualTo(NormalizedTransactionStatus.PENDING_PRICE);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    private static NormalizedTransaction internal(String wallet, String qty, NormalizedLegRole role) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(wallet + qty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setTxHash(TX);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setWalletAddress(wallet);
        tx.setBlockTimestamp(Instant.parse("2026-01-12T10:00:00Z"));
        tx.setContinuityCandidate(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setRole(role);
        flow.setUnitPriceUsd(BigDecimal.ONE);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
