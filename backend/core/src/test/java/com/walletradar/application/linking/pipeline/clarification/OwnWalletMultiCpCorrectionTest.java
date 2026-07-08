package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.application.session.application.SessionWalletAdjacencyService;
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

/**
 * WS-3b: MULTI counterparty own-wallet correction.
 */
@ExtendWith(MockitoExtension.class)
class OwnWalletMultiCpCorrectionTest {

    private static final String WALLET = "0xe612560000000000000000000000000000000001";
    private static final String OWN_WALLET_2 = "0xe612560000000000000000000000000000000002";
    private static final String EXTERNAL = "0xdddd000000000000000000000000000000000099";

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
                mongoOperations, normalizedTransactionRepository,
                accountingUniverseService, sessionWalletAdjacencyService
        );
        lenient().when(normalizedTransactionRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("EXTERNAL_TRANSFER_OUT + MULTI + own-wallet flow cp → INTERNAL_TRANSFER + concrete cp")
    void externalTransferMultiCpOwnWallet_reclassifiedToInternalTransfer() {
        NormalizedTransaction tx = externalTransferWithFlowCp(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, WALLET, OWN_WALLET_2, "-5.0"
        );
        tx.setCounterpartyAddress(FlowCounterpartySupport.MULTI_COUNTERPARTY);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(accountingUniverseService.shareUniverseMembers(WALLET, OWN_WALLET_2)).thenReturn(true);

        int changed = service.reclassifyMultiCpOwnWalletTransfers(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.INTERNAL_TRANSFER);
        assertThat(tx.getCounterpartyAddress()).isEqualTo(OWN_WALLET_2);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("EXTERNAL_TRANSFER_OUT + MULTI + external cp → NOT reclassified")
    void externalTransferMultiCpExternal_notReclassified() {
        NormalizedTransaction tx = externalTransferWithFlowCp(
                NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, WALLET, EXTERNAL, "-5.0"
        );
        tx.setCounterpartyAddress(FlowCounterpartySupport.MULTI_COUNTERPARTY);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));
        when(accountingUniverseService.shareUniverseMembers(WALLET, EXTERNAL)).thenReturn(false);
        when(sessionWalletAdjacencyService.anySessionListsBothAddresses(WALLET, EXTERNAL)).thenReturn(false);

        int changed = service.reclassifyMultiCpOwnWalletTransfers(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("resolveSingleConcreteCounterparty excludes FEE flows and UNKNOWN:* placeholders")
    void resolveSingleConcreteCounterparty_excludesFeeAndSynthetic() {
        NormalizedTransaction tx = new NormalizedTransaction();
        NormalizedTransaction.Flow principal = new NormalizedTransaction.Flow();
        principal.setRole(NormalizedLegRole.TRANSFER);
        principal.setQuantityDelta(new BigDecimal("-10"));
        principal.setCounterpartyAddress(OWN_WALLET_2);

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setQuantityDelta(new BigDecimal("-0.001"));
        fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");

        tx.setFlows(List.of(principal, fee));

        String resolved = OwnWalletBridgeMistypeCorrectionService.resolveSingleConcreteCounterparty(tx);
        assertThat(resolved).isEqualTo(OWN_WALLET_2);
    }

    @Test
    @DisplayName("resolveSingleConcreteCounterparty returns null for multiple distinct principals")
    void resolveSingleConcreteCounterparty_multipleDistinct_returnsNull() {
        NormalizedTransaction tx = new NormalizedTransaction();
        NormalizedTransaction.Flow f1 = new NormalizedTransaction.Flow();
        f1.setRole(NormalizedLegRole.TRANSFER);
        f1.setQuantityDelta(new BigDecimal("-1"));
        f1.setCounterpartyAddress(OWN_WALLET_2);

        NormalizedTransaction.Flow f2 = new NormalizedTransaction.Flow();
        f2.setRole(NormalizedLegRole.TRANSFER);
        f2.setQuantityDelta(new BigDecimal("-1"));
        f2.setCounterpartyAddress(EXTERNAL);

        tx.setFlows(List.of(f1, f2));

        String resolved = OwnWalletBridgeMistypeCorrectionService.resolveSingleConcreteCounterparty(tx);
        assertThat(resolved).isNull();
    }

    private static NormalizedTransaction externalTransferWithFlowCp(
            NormalizedTransactionType type,
            String walletAddress,
            String flowCounterparty,
            String quantity
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-multi-" + type);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress(walletAddress);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setCounterpartyAddress(flowCounterparty);

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setQuantityDelta(new BigDecimal("-0.0005"));
        fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");

        tx.setFlows(List.of(flow, fee));
        return tx;
    }
}
