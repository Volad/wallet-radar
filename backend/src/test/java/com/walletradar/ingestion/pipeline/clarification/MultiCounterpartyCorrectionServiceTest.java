package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
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
 * WS-3b: MultiCounterpartyCorrectionService — de-MULTI and swap-retype phases.
 */
@ExtendWith(MockitoExtension.class)
class MultiCounterpartyCorrectionServiceTest {

    private static final String WALLET = "0xcccc000000000000000000000000000000000001";
    private static final String EXTERNAL_EOA = "0xeeee000000000000000000000000000000000001";
    // Uniswap UniversalRouter
    private static final String UNISWAP_UR = "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private MultiCounterpartyCorrectionService service;

    @BeforeEach
    void setUp() {
        service = new MultiCounterpartyCorrectionService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("de-MULTI: EXTERNAL_TRANSFER_OUT + MULTI + single concrete flow cp → concrete cp stamped")
    void deMulti_singleConcreteCounterparty_stamped() {
        NormalizedTransaction tx = externalTransfer(WALLET, EXTERNAL_EOA, "-10", true);
        tx.setCounterpartyAddress(FlowCounterpartySupport.MULTI_COUNTERPARTY);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int changed = service.deMultiExternalTransfers(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getCounterpartyAddress()).isEqualTo(EXTERNAL_EOA);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("de-MULTI: idempotent — second run finds 0 rows because MULTI already replaced")
    void deMulti_idempotent_secondRunZeroMutations() {
        // After first run, counterpartyAddress is EXTERNAL_EOA, not MULTI → query returns nothing
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());

        int changed = service.deMultiExternalTransfers(50);

        assertThat(changed).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("swap-retype: EXTERNAL_TRANSFER_OUT + Uniswap UR cp + swap-shape → SWAP")
    void retypeSwap_aggregatorCpSwapShape_retypedToSwap() {
        NormalizedTransaction tx = swapShapeTransaction(UNISWAP_UR);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int changed = service.retypeAggregatorSwapMistypes(50);

        assertThat(changed).isEqualTo(1);
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.SWAP);
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("swap-retype: idempotent — already SWAP → 0 mutations")
    void retypeSwap_alreadySwap_zeroMutations() {
        NormalizedTransaction tx = swapShapeTransaction(UNISWAP_UR);
        tx.setType(NormalizedTransactionType.SWAP);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int changed = service.retypeAggregatorSwapMistypes(50);

        assertThat(changed).isZero();
    }

    @Test
    @DisplayName("swap-retype: only-outbound (no inbound flow) → NOT retyped")
    void retypeSwap_onlyOutboundNoInbound_notRetyped() {
        NormalizedTransaction tx = externalTransfer(WALLET, UNISWAP_UR, "-5", false);
        tx.setCounterpartyAddress(UNISWAP_UR);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of(tx));

        int changed = service.retypeAggregatorSwapMistypes(50);

        assertThat(changed).isZero();
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
    }

    // ---------- helpers ----------

    private static NormalizedTransaction externalTransfer(
            String wallet,
            String flowCp,
            String quantity,
            boolean withFee
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-ext-" + flowCp);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setWalletAddress(wallet);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setCounterpartyAddress(flowCp);

        if (withFee) {
            NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
            fee.setRole(NormalizedLegRole.FEE);
            fee.setQuantityDelta(new BigDecimal("-0.001"));
            fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");
            tx.setFlows(List.of(flow, fee));
        } else {
            tx.setFlows(List.of(flow));
        }
        return tx;
    }

    /**
     * Transaction with both inbound and outbound flows (swap shape), cp = aggregator address.
     */
    private static NormalizedTransaction swapShapeTransaction(String aggregatorCp) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-swap-" + aggregatorCp);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setWalletAddress("0xaaaa000000000000000000000000000000000001");
        tx.setCounterpartyAddress(aggregatorCp);

        NormalizedTransaction.Flow out = new NormalizedTransaction.Flow();
        out.setRole(NormalizedLegRole.TRANSFER);
        out.setAssetSymbol("ETH");
        out.setQuantityDelta(new BigDecimal("-1.0"));
        out.setCounterpartyAddress(aggregatorCp);

        NormalizedTransaction.Flow in = new NormalizedTransaction.Flow();
        in.setRole(NormalizedLegRole.TRANSFER);
        in.setAssetSymbol("USDC");
        in.setQuantityDelta(new BigDecimal("2000"));
        in.setCounterpartyAddress(aggregatorCp);

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setQuantityDelta(new BigDecimal("-0.001"));
        fee.setCounterpartyAddress("UNKNOWN:NETWORK_FEE");

        tx.setFlows(List.of(out, in, fee));
        return tx;
    }
}
