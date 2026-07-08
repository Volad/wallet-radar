package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.LiquidStakingFlowSelection;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiquidStakingReplayHandlerTest {

    private final ReplayAssetSupport assetSupport = mock(ReplayAssetSupport.class);
    private final ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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

    @Test
    void selectsFusedCrossAssetEthFamilyTransferPrincipalFlows() {
        // The fused cross-sub-account earn conversion (BybitCanonicalTransactionBuilder
        // #buildCrossSubAccountStakingPair) emits METH out / CMETH in as TRANSFER/TRANSFER — the
        // same shape as the same-sub-account ETH→METH staking control. It must be recognised as a
        // liquid-staking principal pair so the source family basis carries into the received token.
        NormalizedTransaction.Flow methOut = flow(NormalizedLegRole.TRANSFER, "METH", "-0.66865026");
        NormalizedTransaction.Flow cmethIn = flow(NormalizedLegRole.TRANSFER, "CMETH", "0.66865026");
        NormalizedTransaction transaction = transaction(methOut, cmethIn);

        when(assetSupport.assetIdentity(transaction, methOut)).thenReturn("SYMBOL:METH");
        when(assetSupport.assetIdentity(transaction, cmethIn)).thenReturn("SYMBOL:CMETH");

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);

        assertThat(selection.outbound()).hasSize(1);
        assertThat(selection.inbound()).hasSize(1);
        assertThat(selection.outbound().getFirst().flow()).isSameAs(methOut);
        assertThat(selection.inbound().getFirst().flow()).isSameAs(cmethIn);
    }

    @Test
    void fundKeyedStakingDrainsFundInventoryNotEmptyUmbrella() {
        // Fix A.1: the 2025-03-12 "ETH 2.0" cycle. The staking is keyed on :FUND (wallet ends :FUND)
        // and its ETH principal genuinely sits on :FUND (credited by two corridor deposits totalling
        // 0.709). The outbound flow keys to the umbrella, but the umbrella holds no ETH — draining it
        // would mint the METH receipt at $0. The handler must drain the :FUND ETH inventory instead so
        // the staked-principal basis carries into the METH receipt.
        AssetKey ethUmbrella = new AssetKey("BYBIT:33625378", null, "eth", "ETH", "FAMILY:ETH");
        AssetKey ethFund = new AssetKey("BYBIT:33625378:FUND", null, "eth", "ETH", "FAMILY:ETH");
        AssetKey methUmbrella = new AssetKey("BYBIT:33625378", null, "meth", "METH", "FAMILY:ETH");

        NormalizedTransaction.Flow ethOut = flow(NormalizedLegRole.TRANSFER, "ETH", "-0.709");
        NormalizedTransaction.Flow methIn = flow(NormalizedLegRole.TRANSFER, "METH", "0.66865026");
        NormalizedTransaction transaction = transaction(ethOut, methIn);
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        transaction.setWalletAddress("BYBIT:33625378:FUND");
        transaction.setBlockTimestamp(Instant.parse("2025-03-12T20:08:00Z"));

        when(assetSupport.assetIdentity(transaction, ethOut)).thenReturn("SYMBOL:ETH");
        when(assetSupport.assetIdentity(transaction, methIn)).thenReturn("SYMBOL:METH");
        when(assetSupport.assetKey(transaction, ethOut)).thenReturn(ethUmbrella);
        when(assetSupport.assetKey(transaction, methIn)).thenReturn(methUmbrella);

        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
        PositionState fund = replayState.position(ethFund);
        fund.setQuantity(new BigDecimal("0.709"));
        fund.setTotalCostBasisUsd(new BigDecimal("1350.00"));
        fund.setUncoveredQuantity(BigDecimal.ZERO);
        fund.setPerWalletAvco(new BigDecimal("1904.09"));

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);
        assertThat(selection.outbound()).hasSize(1);
        handler.applySelected(transaction, selection, replayState);

        assertThat(replayState.positions().position(ethFund).quantity())
                .as(":FUND ETH drained by the staking outbound")
                .isEqualByComparingTo("0");
        assertThat(replayState.positions().position(ethUmbrella).quantity())
                .as("empty umbrella ETH untouched (not minted negative)")
                .isEqualByComparingTo("0");
        PositionState meth = replayState.positions().position(methUmbrella);
        assertThat(meth.quantity()).isEqualByComparingTo("0.66865026");
        assertThat(meth.totalCostBasisUsd())
                .as("staked ETH basis carried into the METH receipt (not $0)")
                .isGreaterThan(new BigDecimal("1000"));
    }

    @Test
    void accountRefFundKeyedStakingDrainsFundWhenTransactionWalletIsUmbrella() {
        // ADR-042: the 2025-04-18 STAKING_DEPOSIT. The transaction walletAddress is the PLAIN
        // umbrella (BYBIT:33625378) — so the Fix A.1 transaction-walletAddress :FUND guard never
        // fires — but the outbound flow's accountRef still names the :FUND sub-account that funded
        // the ETH principal. The shared accountRef redirect must drain :FUND (not the umbrella), so
        // the sub-account is left at 0 (no phantom) and the staked basis carries into the receipt.
        AssetKey ethUmbrella = new AssetKey("BYBIT:33625378", null, "eth", "ETH", "FAMILY:ETH");
        AssetKey ethFund = new AssetKey("BYBIT:33625378:FUND", null, "eth", "ETH", "FAMILY:ETH");
        AssetKey methUmbrella = new AssetKey("BYBIT:33625378", null, "meth", "METH", "FAMILY:ETH");

        NormalizedTransaction.Flow ethOut = flow(NormalizedLegRole.TRANSFER, "ETH", "-0.6929745940390085");
        ethOut.setAccountRef("BYBIT:33625378:FUND");
        NormalizedTransaction.Flow methIn = flow(NormalizedLegRole.TRANSFER, "METH", "0.66865026");
        methIn.setAccountRef("BYBIT:33625378:FUND");
        NormalizedTransaction transaction = transaction(ethOut, methIn);
        transaction.setSource(NormalizedTransactionSource.BYBIT);
        // Plain umbrella — Fix A.1's transaction-wallet :FUND guard cannot fire here.
        transaction.setWalletAddress("BYBIT:33625378");
        transaction.setBlockTimestamp(Instant.parse("2025-04-18T00:00:00Z"));

        when(assetSupport.assetIdentity(transaction, ethOut)).thenReturn("SYMBOL:ETH");
        when(assetSupport.assetIdentity(transaction, methIn)).thenReturn("SYMBOL:METH");
        when(assetSupport.assetKey(transaction, ethOut)).thenReturn(ethUmbrella);
        when(assetSupport.assetKey(transaction, methIn)).thenReturn(methUmbrella);

        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
        // Principal genuinely sits on :FUND; the umbrella holds nothing.
        PositionState fund = replayState.position(ethFund);
        fund.setQuantity(new BigDecimal("0.6929745940390085"));
        fund.setTotalCostBasisUsd(new BigDecimal("1104.35"));
        fund.setUncoveredQuantity(BigDecimal.ZERO);
        fund.setPerWalletAvco(new BigDecimal("1593.64"));

        LiquidStakingFlowSelection selection = handler.selectPrincipalFlows(transaction);
        assertThat(selection.outbound()).hasSize(1);
        handler.applySelected(transaction, selection, replayState);

        assertThat(replayState.positions().position(ethFund).quantity())
                .as(":FUND ETH drained via flow.accountRef redirect")
                .isEqualByComparingTo("0");
        assertThat(replayState.positions().position(ethUmbrella).quantity())
                .as("empty umbrella ETH untouched")
                .isEqualByComparingTo("0");
        PositionState meth = replayState.positions().position(methUmbrella);
        assertThat(meth.quantity()).isEqualByComparingTo("0.66865026");
        assertThat(meth.totalCostBasisUsd())
                .as("staked ETH basis carried into the METH receipt (not $0)")
                .isGreaterThan(new BigDecimal("1000"));
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
