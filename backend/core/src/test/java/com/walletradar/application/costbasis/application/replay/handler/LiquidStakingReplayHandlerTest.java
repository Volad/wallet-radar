package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiquidStakingReplayHandlerTest {

    private final ReplayAssetSupport assetSupport = new ReplayAssetSupport();
    private final ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
    private final LiquidStakingReplayHandler handler = new LiquidStakingReplayHandler(
            assetSupport,
            flowSupport,
            new ReplaySettlementAllocator(assetSupport, flowSupport)
    );

    @Test
    void avaxToSavaxCrossCanonicalDoesNotSelectPrincipalFlows() {
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.SELL, "AVAX", "-1"),
                flow(NormalizedLegRole.BUY, "sAVAX", "0.95")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).isEmpty();
        assertThat(selection.inbound()).isEmpty();
    }

    @Test
    void methToCmethSelectsPrincipalFlowsAsC1SameFamily() {
        // CMETH is now C1 sharing FAMILY:METH — METH→CMETH routes through LiquidStakingReplayHandler
        // as REALLOCATE_OUT/IN (same-family basis carry), not cross-canonical DISPOSE+ACQUIRE.
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.TRANSFER, "METH", "-0.66865026"),
                flow(NormalizedLegRole.TRANSFER, "CMETH", "0.66865026")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).hasSize(1);
        assertThat(selection.outbound().getFirst().flow().getAssetSymbol()).isEqualTo("METH");
        assertThat(selection.inbound()).hasSize(1);
        assertThat(selection.inbound().getFirst().flow().getAssetSymbol()).isEqualTo("CMETH");
    }

    @Test
    void ethToCmethStakingDepositDoesNotSelectPrincipalFlows() {
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1.0"),
                flow(NormalizedLegRole.TRANSFER, "CMETH", "0.97")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).isEmpty();
        assertThat(selection.inbound()).isEmpty();
    }

    private NormalizedTransaction transaction(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        transaction.setFlows(List.of(flows));
        return transaction;
    }

    private NormalizedTransaction.Flow flow(NormalizedLegRole role, String symbol, String quantity) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(symbol.toLowerCase());
        flow.setQuantityDelta(new BigDecimal(quantity));
        return flow;
    }
}
