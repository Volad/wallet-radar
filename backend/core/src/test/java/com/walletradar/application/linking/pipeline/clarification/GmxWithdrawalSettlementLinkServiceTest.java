package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NEW-09: verifies the deterministic pairing of two-step GMX GLV/GM withdrawal settlements
 * to their open {@code LP_EXIT_REQUEST}. Anchors keyed to the live wallet
 * {@code 0x1a87f12…d693f} and the GLV settlement/exit-request tx pair used only as evidence.
 */
@ExtendWith(MockitoExtension.class)
class GmxWithdrawalSettlementLinkServiceTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String GLV_CORR_ID = "gmx-lp:arbitrum:glv-weth-usdc";
    private static final String SETTLEMENT_TX =
            "0xf3581fb98799bb1d55ec08a72dfb6668ae4009f219434e734e8a9db0388ec374";
    private static final String EXIT_REQUEST_TX =
            "0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec";
    private static final Instant SETTLEMENT_TS = Instant.parse("2026-01-29T19:20:13Z");
    private static final Instant EXIT_REQUEST_TS = Instant.parse("2026-01-29T19:20:09Z");

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private GmxWithdrawalSettlementLinkService service;

    @BeforeEach
    void setUp() {
        service = new GmxWithdrawalSettlementLinkService(mongoOperations, normalizedTransactionRepository);
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(false);
    }

    @Test
    @DisplayName("T-09a: GLV settlement paired to open exit-request reclassifies to LP_EXIT_SETTLEMENT with carried basis")
    void linksGlvSettlementToOpenExitRequest() {
        NormalizedTransaction settlement = glvSettlement();
        NormalizedTransaction request = openExitRequest();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(request));

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isEqualTo(1);
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_SETTLEMENT);
        assertThat(settlement.getCorrelationId()).isEqualTo(GLV_CORR_ID);
        assertThat(settlement.getProtocolName()).isEqualTo("GMX");
        assertThat(settlement.getCounterpartyType()).isEqualTo("PROTOCOL");
        assertThat(settlement.getMissingDataReasons())
                .doesNotContain("GMX_EXECUTION_FEE_REFUND")
                .contains("COUNTERPARTY_ADDRESS_INFERRED");
        assertThat(settlement.getFlows()).allSatisfy(flow -> {
            assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
            assertThat(flow.getUnitPriceUsd()).isNull();
            assertThat(flow.getValueUsd()).isNull();
            assertThat(flow.getPriceSource()).isNull();
            assertThat(flow.getRealisedPnlUsd()).isNull();
        });
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("T-09d: GMX fee-refund inflow with no matching open exit-request stays EXTERNAL_TRANSFER_IN")
    void standaloneFeeRefundStaysUnlinked() {
        NormalizedTransaction settlement = glvSettlement();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of());

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isZero();
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(settlement.getCorrelationId()).isNull();
        assertThat(settlement.getMissingDataReasons()).contains("GMX_EXECUTION_FEE_REFUND");
        assertThat(settlement.getFlows()).allSatisfy(flow ->
                assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.BUY));
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("T-09e: two distinct open positions in-window leaves the settlement unlinked (no guess)")
    void ambiguousOpenRequestsLeftUnlinked() {
        NormalizedTransaction settlement = glvSettlement();
        NormalizedTransaction requestA = openExitRequest();
        NormalizedTransaction requestB = openExitRequest();
        requestB.setId(EXIT_REQUEST_TX + ":B");
        requestB.setTxHash("0xdeadbeef");
        requestB.setCorrelationId("gmx-lp:arbitrum:gm-eth-usdc");
        requestB.setBlockTimestamp(EXIT_REQUEST_TS.plusSeconds(1));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(requestB, requestA));

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isZero();
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(settlement.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("T-09 guardrail: an already-settled position is not re-linked")
    void alreadySettledPositionNotRelinked() {
        NormalizedTransaction settlement = glvSettlement();
        NormalizedTransaction request = openExitRequest();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(request));
        when(mongoOperations.exists(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(true);

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isZero();
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    @Test
    @DisplayName("T-09f: multi-leg settlement (ETH + USDC) reshapes every inbound principal leg to TRANSFER")
    void multiLegSettlementReshapesAllPrincipalLegs() {
        NormalizedTransaction settlement = multiLegSettlement();
        NormalizedTransaction request = openExitRequest();
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement), List.of(request));

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isEqualTo(1);
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_SETTLEMENT);
        assertThat(settlement.getCorrelationId()).isEqualTo(GLV_CORR_ID);
        List<NormalizedTransaction.Flow> principals = settlement.getFlows().stream()
                .filter(f -> f.getRole() != NormalizedLegRole.FEE)
                .toList();
        assertThat(principals).hasSize(2);
        assertThat(principals).allSatisfy(flow -> {
            assertThat(flow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
            assertThat(flow.getValueUsd()).isNull();
        });
        // The FEE leg is untouched.
        NormalizedTransaction.Flow fee = settlement.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.FEE)
                .findFirst()
                .orElseThrow();
        assertThat(fee.getRole()).isEqualTo(NormalizedLegRole.FEE);
    }

    @Test
    @DisplayName("outbound principal (not a pure inflow) is never treated as a settlement")
    void outboundPrincipalIsNotASettlement() {
        NormalizedTransaction settlement = glvSettlement();
        settlement.getFlows().getFirst().setQuantityDelta(new BigDecimal("-0.5"));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of(settlement));

        int linked = service.linkOutstandingWithdrawalSettlements(50);

        assertThat(linked).isZero();
        assertThat(settlement.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
    }

    private static NormalizedTransaction glvSettlement() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(SETTLEMENT_TX + ":ARBITRUM:" + WALLET);
        tx.setTxHash(SETTLEMENT_TX);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress(WALLET);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setProtocolName("GMX V2");
        tx.setCounterpartyType("PROTOCOL");
        tx.setBlockTimestamp(SETTLEMENT_TS);
        tx.setFlows(new ArrayList<>(List.of(
                ethBuyLeg("0.009864288325390225", "2817.58"),
                ethBuyLeg("0.009996327410602506", "2817.58"),
                ethBuyLeg("0.001006008387727200", "2817.58")
        )));
        tx.setMissingDataReasons(new ArrayList<>(List.of(
                "COUNTERPARTY_ADDRESS_INFERRED", "GMX_EXECUTION_FEE_REFUND")));
        return tx;
    }

    private static NormalizedTransaction multiLegSettlement() {
        NormalizedTransaction tx = glvSettlement();
        tx.getFlows().clear();
        tx.getFlows().add(ethBuyLeg("0.02", "2817.58"));
        NormalizedTransaction.Flow usdc = buyLeg("USDC", "45.0", "1.0");
        tx.getFlows().add(usdc);
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(new BigDecimal("-0.00002"));
        tx.getFlows().add(fee);
        return tx;
    }

    private static NormalizedTransaction.Flow ethBuyLeg(String qty, String price) {
        return buyLeg("ETH", qty, price);
    }

    private static NormalizedTransaction.Flow buyLeg(String symbol, String qty, String price) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setUnitPriceUsd(new BigDecimal(price));
        flow.setValueUsd(new BigDecimal(qty).multiply(new BigDecimal(price)));
        flow.setPriceSource(com.walletradar.domain.common.PriceSource.DZENGI);
        flow.setRealisedPnlUsd(BigDecimal.ZERO);
        return flow;
    }

    private static NormalizedTransaction openExitRequest() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(EXIT_REQUEST_TX + ":ARBITRUM:" + WALLET);
        tx.setTxHash(EXIT_REQUEST_TX);
        tx.setNetworkId(NetworkId.ARBITRUM);
        tx.setWalletAddress(WALLET);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LP_EXIT_REQUEST);
        tx.setProtocolName("GMX");
        tx.setCorrelationId(GLV_CORR_ID);
        tx.setBlockTimestamp(EXIT_REQUEST_TS);
        return tx;
    }
}
