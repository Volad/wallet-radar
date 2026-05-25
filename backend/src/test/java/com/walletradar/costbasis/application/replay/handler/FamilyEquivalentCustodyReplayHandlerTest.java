package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
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
    private final ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
    private final FamilyEquivalentCustodyReplayHandler handler = new FamilyEquivalentCustodyReplayHandler(
            assetSupport,
            flowSupport,
            new ContinuityCarryService(replayEngine, flowSupport),
            keyFactory
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

    @Test
    void aaveMantleWethSupplyMintsReceiptAndPairsByFamily() {
        // Cycle/9 S6: Aave V3 supply on Mantle — outbound WETH (to Pool), inbound aManWETH.
        // Simple 1+1 within FAMILY:ETH; handler must still pair correctly after multi-outbound
        // weakening.
        NormalizedTransaction.Flow wethOut = flow(NormalizedLegRole.TRANSFER, "WETH", "-1.0");
        NormalizedTransaction.Flow aManWethIn = flow(NormalizedLegRole.TRANSFER, "aManWETH", "1.0");
        NormalizedTransaction transaction = transaction(NormalizedTransactionType.LENDING_DEPOSIT, wethOut, aManWethIn);

        when(assetSupport.assetIdentity(transaction, wethOut)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, aManWethIn)).thenReturn("MANTLE:aManWETH");

        SimpleFamilyCustodySelection selection = handler.selectFlows(transaction);

        assertThat(selection.pairs()).hasSize(1);
        assertThat(selection.pairs().getFirst().outbound().flow()).isSameAs(wethOut);
        assertThat(selection.pairs().getFirst().inbound().flow()).isSameAs(aManWethIn);
    }

    @Test
    void aaveMantleWithdrawWithSameAssetDustRefundStillPairsByNetSign() {
        // Cycle/9 S6: when an Aave V3 withdraw briefly bounces a tiny WETH refund back into
        // the wallet (or a same-asset dust appears), the family has 2 outbound legs sharing
        // FAMILY:ETH (aManWETH burn + WETH dust out). Net signs: aManWETH is strictly negative,
        // WETH cancels to ~0 because a same-asset inbound balances it. Principal pair must
        // resolve aManWETH→underlying.
        NormalizedTransaction.Flow aManWethOut = flow(NormalizedLegRole.TRANSFER, "aManWETH", "-2.0");
        NormalizedTransaction.Flow wethRefundOut = flow(NormalizedLegRole.TRANSFER, "WETH", "-0.001");
        NormalizedTransaction.Flow wethRefundIn = flow(NormalizedLegRole.TRANSFER, "WETH", "0.001");
        NormalizedTransaction.Flow wethPrincipalIn = flow(NormalizedLegRole.TRANSFER, "WETH", "2.0");
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.LENDING_WITHDRAW,
                aManWethOut,
                wethRefundOut,
                wethRefundIn,
                wethPrincipalIn
        );

        when(assetSupport.assetIdentity(transaction, aManWethOut)).thenReturn("MANTLE:aManWETH");
        when(assetSupport.assetIdentity(transaction, wethRefundOut)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, wethRefundIn)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, wethPrincipalIn)).thenReturn("MANTLE:WETH");

        SimpleFamilyCustodySelection selection = handler.selectFlows(transaction);

        assertThat(selection.pairs()).hasSize(1);
        assertThat(selection.pairs().getFirst().outbound().flow()).isSameAs(aManWethOut);
        assertThat(selection.pairs().getFirst().inbound().flow()).isSameAs(wethPrincipalIn);
    }

    @Test
    void multiOutboundWithoutNetNegativeAssetReturnsEmpty() {
        // Edge case: every outbound is perfectly cancelled by same-asset inbound (e.g. simple
        // pass-through). No net principal exists → handler must defer to generic replay
        // rather than incorrectly pairing arbitrary flows.
        NormalizedTransaction.Flow wethOut = flow(NormalizedLegRole.TRANSFER, "WETH", "-1.0");
        NormalizedTransaction.Flow wethIn = flow(NormalizedLegRole.TRANSFER, "WETH", "1.0");
        NormalizedTransaction.Flow aManWethOut = flow(NormalizedLegRole.TRANSFER, "aManWETH", "-2.0");
        NormalizedTransaction.Flow aManWethIn = flow(NormalizedLegRole.TRANSFER, "aManWETH", "2.0");
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.LENDING_DEPOSIT,
                wethOut,
                wethIn,
                aManWethOut,
                aManWethIn
        );

        when(assetSupport.assetIdentity(transaction, wethOut)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, wethIn)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, aManWethOut)).thenReturn("MANTLE:aManWETH");
        when(assetSupport.assetIdentity(transaction, aManWethIn)).thenReturn("MANTLE:aManWETH");

        SimpleFamilyCustodySelection selection = handler.selectFlows(transaction);

        assertThat(selection.pairs()).isEmpty();
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
