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
    void avaxToSavaxIntraClusterSelectsPrincipalFlows() {
        // ADR-083: AVAX and sAVAX both resolve to CLUSTER:AVAX_STAKING → cluster-carry (not realize).
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.SELL, "AVAX", "-1"),
                flow(NormalizedLegRole.BUY, "sAVAX", "0.95")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).hasSize(1);
        assertThat(selection.outbound().getFirst().flow().getAssetSymbol()).isEqualTo("AVAX");
        assertThat(selection.inbound()).hasSize(1);
        assertThat(selection.inbound().getFirst().flow().getAssetSymbol()).isEqualTo("sAVAX");
    }

    @Test
    void methToCmethSelectsPrincipalFlowsAsDegenerateSingleClusterCase() {
        // ADR-083 degenerate case: METH and CMETH both FAMILY:METH → CLUSTER:ETH_STAKING carry.
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
    void ethToCmethIntraClusterSelectsPrincipalFlows() {
        // ADR-083: ETH (FAMILY:ETH) and CMETH (FAMILY:METH) both resolve to CLUSTER:ETH_STAKING.
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.TRANSFER, "ETH", "-1.0"),
                flow(NormalizedLegRole.TRANSFER, "CMETH", "0.97")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).hasSize(1);
        assertThat(selection.inbound()).hasSize(1);
    }

    @Test
    void crossClusterEthToSolDoesNotSelectPrincipalFlows() {
        // ADR-083: ETH (ETH cluster) → SOL (SOL cluster) is cross-cluster → realize, not carry.
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.SELL, "ETH", "-1.0"),
                flow(NormalizedLegRole.BUY, "SOL", "20.0")
        );

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).isEmpty();
        assertThat(selection.inbound()).isEmpty();
    }

    @Test
    void clusterToNonClusterEthToUsdtDoesNotSelectPrincipalFlows() {
        // ADR-083: ETH (cluster) → USDT (non-cluster) is an exit/sale → realize, not carry.
        NormalizedTransaction transaction = transaction(
                flow(NormalizedLegRole.SELL, "ETH", "-1.0"),
                flow(NormalizedLegRole.BUY, "USDT", "3000.0")
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
