package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FamilyEquivalentCustodyReplayHandlerTest {

    private final ReplayAssetSupport assetSupport = mock(ReplayAssetSupport.class);
    private final GenericFlowReplayEngine replayEngine = new GenericFlowReplayEngine();
    private final ReplayFlowSupport flowSupport = new ReplayFlowSupport(replayEngine);
    private final FamilyEquivalentCustodyReplayHandler handler = new FamilyEquivalentCustodyReplayHandler(
            assetSupport,
            flowSupport,
            new ContinuityCarryService(replayEngine, flowSupport)
    );

    @Test
    void selectsLegacySellBuyPrincipalPairAndLeavesExplicitExcessBuyForGenericReplay() {
        NormalizedTransaction.Flow sharesOut = flow(NormalizedLegRole.SELL, "eUSDC-1", "-100");
        NormalizedTransaction.Flow principalIn = flow(NormalizedLegRole.BUY, "USDC", "99");
        NormalizedTransaction.Flow excessIn = flow(NormalizedLegRole.BUY, "USDC", "1");
        NormalizedTransaction transaction = transaction(NormalizedTransactionType.VAULT_WITHDRAW, sharesOut, principalIn, excessIn);

        when(assetSupport.assetIdentity(transaction, sharesOut)).thenReturn("ARBITRUM:eUSDC");
        when(assetSupport.assetIdentity(transaction, principalIn)).thenReturn("ARBITRUM:USDC");
        when(assetSupport.assetIdentity(transaction, excessIn)).thenReturn("ARBITRUM:USDC");

        SimpleFamilyCustodySelection selection = handler.selectFlows(transaction);

        assertThat(selection.pairs()).hasSize(1);
        assertThat(selection.pairs().getFirst().outbound().flow()).isSameAs(sharesOut);
        assertThat(selection.pairs().getFirst().inbound().flow()).isSameAs(principalIn);
        assertThat(selection.selectedByIndex()).containsOnlyKeys(0, 1);
    }

    private NormalizedTransaction transaction(NormalizedTransactionType type, NormalizedTransaction.Flow... flows) {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setType(type);
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
