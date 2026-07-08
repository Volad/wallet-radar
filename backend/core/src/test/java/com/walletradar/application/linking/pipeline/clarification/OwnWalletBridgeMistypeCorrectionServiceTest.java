package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionWalletAdjacencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnWalletBridgeMistypeCorrectionServiceTest {

    // Evidence anchor only: a member wallet that appeared as a BRIDGE_OUT counterparty.
    private static final String OWN_WALLET = "0xe612560000000000000000000000000000000001";
    private static final String OWN_COUNTERPARTY = "0xe612560000000000000000000000000000000002";
    private static final String EXTERNAL_ROUTER = "0xcd74f91e4d2a49903462d58d6951136a527a5dea";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseService accountingUniverseService;
    @Mock
    private SessionWalletAdjacencyService sessionWalletAdjacencyService;

    private OwnWalletBridgeMistypeCorrectionService service;

    @BeforeEach
    void setUp() {
        service = new OwnWalletBridgeMistypeCorrectionService(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseService,
                sessionWalletAdjacencyService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("BRIDGE_OUT to another own/member wallet is reclassified as INTERNAL_TRANSFER")
    void reclassifiesOwnWalletBridgeOutToInternalTransfer() {
        NormalizedTransaction tx = bridgeLeg(NormalizedTransactionType.BRIDGE_OUT, OWN_WALLET, OWN_COUNTERPARTY, "-0.0111");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(accountingUniverseService.shareUniverseMembers(OWN_WALLET, OWN_COUNTERPARTY)).thenReturn(true);

        int changed = service.reclassifyOwnWalletBridgeMistypes(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(tx.getContinuityCandidate()).isTrue();
        assertThat(tx.getFlows().getFirst().getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("BRIDGE_OUT to a genuine external bridge router stays BRIDGE_OUT")
    void leavesExternalBridgeOutUntouched() {
        NormalizedTransaction tx = bridgeLeg(NormalizedTransactionType.BRIDGE_OUT, OWN_WALLET, EXTERNAL_ROUTER, "-3.0");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(accountingUniverseService.shareUniverseMembers(OWN_WALLET, EXTERNAL_ROUTER)).thenReturn(false);
        when(sessionWalletAdjacencyService.anySessionListsBothAddresses(OWN_WALLET, EXTERNAL_ROUTER)).thenReturn(false);

        int changed = service.reclassifyOwnWalletBridgeMistypes(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("degenerate self-loop (wallet == counterparty) is not reclassified")
    void leavesSelfLoopUntouched() {
        NormalizedTransaction tx = bridgeLeg(NormalizedTransactionType.BRIDGE_OUT, OWN_WALLET, OWN_WALLET, "-1.0");
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int changed = service.reclassifyOwnWalletBridgeMistypes(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.BRIDGE_OUT);
    }

    private static NormalizedTransaction bridgeLeg(
            NormalizedTransactionType type,
            String walletAddress,
            String counterpartyAddress,
            String quantity
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-" + type);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress(walletAddress);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setCounterpartyAddress(counterpartyAddress);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
