package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiquidStakingReplayHandlerTest {

    private final ReplayAssetSupport assetSupport = mock(ReplayAssetSupport.class);
    private final ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
    private final LiquidStakingReplayHandler handler = new LiquidStakingReplayHandler(
            assetSupport,
            flowSupport,
            new ReplaySettlementAllocator(assetSupport, flowSupport)
    );

    @Test
    void selectsLegacySellBuyLiquidStakingPrincipalFlows() {
        NormalizedTransaction.Flow avaxOut = flow(NormalizedLegRole.SELL, "AVAX", "-1");
        NormalizedTransaction.Flow savaxIn = flow(NormalizedLegRole.BUY, "sAVAX", "0.95");
        NormalizedTransaction transaction = transaction(avaxOut, savaxIn);

        when(assetSupport.assetIdentity(transaction, avaxOut)).thenReturn("AVALANCHE:AVAX");
        when(assetSupport.assetIdentity(transaction, savaxIn)).thenReturn("AVALANCHE:sAVAX");

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).hasSize(1);
        assertThat(selection.inbound()).hasSize(1);
        assertThat(selection.outbound().getFirst().flow()).isSameAs(avaxOut);
        assertThat(selection.inbound().getFirst().flow()).isSameAs(savaxIn);
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
