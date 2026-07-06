package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.IndexedFlow;
import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodyPair;
import com.walletradar.costbasis.application.replay.model.SimpleFamilyCustodySelection;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
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

class FamilyEquivalentCustodyReplayHandlerTest {

    private final ReplayAssetSupport assetSupport = mock(ReplayAssetSupport.class);
    private final GenericFlowReplayEngine replayEngine = new GenericFlowReplayEngine(null);
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
    void aaveUsdcDepositWithIndexAccrualPairsPrincipalTransferAndSkipsBuyMint() {
        NormalizedTransaction.Flow usdcOut = flow(NormalizedLegRole.TRANSFER, "USDC", "-1266.468");
        NormalizedTransaction.Flow aTokenIn = flow(NormalizedLegRole.TRANSFER, "aAvaUSDC", "1266.468");
        NormalizedTransaction.Flow accrualIn = flow(NormalizedLegRole.BUY, "aAvaUSDC", "0.042");
        NormalizedTransaction transaction = transaction(
                NormalizedTransactionType.LENDING_DEPOSIT,
                usdcOut,
                aTokenIn,
                accrualIn
        );

        when(assetSupport.assetIdentity(transaction, usdcOut)).thenReturn("AVALANCHE:0xusdc");
        when(assetSupport.assetIdentity(transaction, aTokenIn)).thenReturn("AVALANCHE:0xaave");
        when(assetSupport.assetIdentity(transaction, accrualIn)).thenReturn("AVALANCHE:0xaave");

        SimpleFamilyCustodySelection selection = handler.selectFlows(transaction);

        assertThat(selection.pairs()).hasSize(1);
        assertThat(selection.pairs().getFirst().outbound().flow()).isSameAs(usdcOut);
        assertThat(selection.pairs().getFirst().inbound().flow()).isSameAs(aTokenIn);
        // The BUY accrual (index 2) is suppressed: added to selectedByIndex so that
        // replayGenericFlowsSkipping cannot process it as a second REALLOCATE_IN.
        // This prevents cost basis double-counting (Silo Finance regression).
        assertThat(selection.selectedByIndex()).containsOnlyKeys(0, 1, 2);
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

    @Test
    void protocolCustodyWithdrawWithEmptyPoolProducesAcquireNotReallocationForStablecoin() {
        // Paradex L1 Core withdraw: 1,266 USDC returned to wallet.
        // The corresponding deposit either predates the backfill window or was never tracked,
        // so the outbound receipt position has $0 basis (empty pool).
        // Fix: handler must emit ACQUIRE (at $1/USDC) instead of REALLOCATE_IN with $0.
        NormalizedTransaction.Flow receiptOut = flow(NormalizedLegRole.TRANSFER, "pxUSDC", "-1266");
        NormalizedTransaction.Flow usdcIn = flow(NormalizedLegRole.TRANSFER, "USDC", "1266");
        usdcIn.setUnitPriceUsd(new BigDecimal("1.0"));

        NormalizedTransaction transaction = transaction(NormalizedTransactionType.PROTOCOL_CUSTODY_WITHDRAW, receiptOut, usdcIn);
        transaction.setBlockTimestamp(Instant.ofEpochSecond(1700000000));

        String wallet = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
        AssetKey receiptKey = new AssetKey(wallet, NetworkId.ETHEREUM, "pxusdc", "pxUSDC", "ETHEREUM:pxUSDC");
        AssetKey usdcKey = new AssetKey(wallet, NetworkId.ETHEREUM, "usdc", "USDC", "ETHEREUM:USDC");

        when(assetSupport.assetKey(transaction, receiptOut)).thenReturn(receiptKey);
        when(assetSupport.assetKey(transaction, usdcIn)).thenReturn(usdcKey);

        // Both asset identities needed by selectFlows()
        when(assetSupport.assetIdentity(transaction, receiptOut)).thenReturn("ETHEREUM:pxUSDC");
        when(assetSupport.assetIdentity(transaction, usdcIn)).thenReturn("ETHEREUM:USDC");

        // Build a pre-selected pair directly (outbound receipt, inbound USDC).
        // outbound and inbound must be in the same "FAMILY:USDC" family per isPrincipalCandidate.
        // We bypass selectFlows() here because the test validates applySelected() behaviour only.
        IndexedFlow outboundIndexed = new IndexedFlow(0, receiptOut);
        IndexedFlow inboundIndexed = new IndexedFlow(1, usdcIn);
        SimpleFamilyCustodySelection selection = new SimpleFamilyCustodySelection(
                List.of(new SimpleFamilyCustodyPair(outboundIndexed, inboundIndexed)),
                Map.of(0, outboundIndexed, 1, inboundIndexed)
        );

        List<AssetLedgerPoint> collected = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("test-universe", collected, Instant.now());
        ReplayExecutionState replayState = new ReplayExecutionState(
                com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan.empty(),
                collector
        );

        handler.applySelected(transaction, selection, replayState);

        // The outbound position was empty ($0 basis / $0 cost) but the missing quantity is
        // tracked as a shortfall → REALLOCATE_OUT is still emitted (position changed).
        // The inbound MUST produce exactly one ACQUIRE ledger point for USDC with positive basis.
        // There must be NO REALLOCATE_IN point — that was the old broken path.
        assertThat(collected).isNotEmpty();
        assertThat(collected).noneMatch(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        List<AssetLedgerPoint> acquirePoints = collected.stream()
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE)
                .toList();
        assertThat(acquirePoints).hasSize(1);

        AssetLedgerPoint usdcAcquirePoint = acquirePoints.getFirst();
        assertThat(usdcAcquirePoint.getAccountingAssetIdentity()).contains("USDC");
        // USDC $1/unit × 1266 = $1266 cost basis delta
        assertThat(usdcAcquirePoint.getCostBasisDeltaUsd())
                .isNotNull()
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void lendingDepositCarriesFullUnderlyingBasisIntoReceiptAndConservesTotal() {
        // F-2: ETH→aWETH (Aave Mantle supply) must carry the underlying basis into the receipt
        // token via TRANSFER continuity. Basis is REMOVED from the underlying and RESTORED on the
        // receipt 1:1 — conserved, never duplicated, never depressed. A depressed aToken basis
        // (e.g. the audited $656/u vs true ~$2,664/u) is a symptom of an already-depressed source
        // AVCO, not of this carry; this test pins the carry contract so regressions surface here.
        NormalizedTransaction.Flow wethOut = flow(NormalizedLegRole.TRANSFER, "WETH", "-1.0");
        NormalizedTransaction.Flow aManWethIn = flow(NormalizedLegRole.TRANSFER, "aManWETH", "1.0");
        NormalizedTransaction transaction = transaction(NormalizedTransactionType.LENDING_DEPOSIT, wethOut, aManWethIn);
        transaction.setBlockTimestamp(Instant.ofEpochSecond(1700000002));

        String wallet = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
        AssetKey wethKey = new AssetKey(wallet, NetworkId.MANTLE, "0xweth", "WETH", "FAMILY:ETH");
        AssetKey aWethKey = new AssetKey(wallet, NetworkId.MANTLE, "0xaweth", "aManWETH", "FAMILY:ETH");

        when(assetSupport.assetKey(transaction, wethOut)).thenReturn(wethKey);
        when(assetSupport.assetKey(transaction, aManWethIn)).thenReturn(aWethKey);
        when(assetSupport.assetIdentity(transaction, wethOut)).thenReturn("MANTLE:WETH");
        when(assetSupport.assetIdentity(transaction, aManWethIn)).thenReturn("MANTLE:aManWETH");

        List<AssetLedgerPoint> collected = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("test-universe", collected, Instant.now());
        ReplayExecutionState replayState = new ReplayExecutionState(
                com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan.empty(),
                collector
        );

        // Seed the source WETH position with a genuine $2,664/u lot (1.0 unit, $2,664 basis).
        com.walletradar.costbasis.application.replay.model.PositionState wethPosition = replayState.position(wethKey);
        wethPosition.setQuantity(new BigDecimal("1.0"));
        wethPosition.setTotalCostBasisUsd(new BigDecimal("2664.00"));
        wethPosition.setPerWalletAvco(new BigDecimal("2664.00"));

        IndexedFlow outboundIndexed = new IndexedFlow(0, wethOut);
        IndexedFlow inboundIndexed = new IndexedFlow(1, aManWethIn);
        SimpleFamilyCustodySelection selection = new SimpleFamilyCustodySelection(
                List.of(new SimpleFamilyCustodyPair(outboundIndexed, inboundIndexed)),
                Map.of(0, outboundIndexed, 1, inboundIndexed)
        );

        handler.applySelected(transaction, selection, replayState);

        com.walletradar.costbasis.application.replay.model.PositionState aWethPosition = replayState.position(aWethKey);
        // Receipt inherits the FULL underlying basis — not a depressed fraction.
        assertThat(aWethPosition.totalCostBasisUsd()).isEqualByComparingTo(new BigDecimal("2664.00"));
        assertThat(aWethPosition.quantity()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(aWethPosition.perWalletAvco()).isEqualByComparingTo(new BigDecimal("2664.00"));
        // Underlying basis is fully removed (conserved, not duplicated).
        assertThat(wethPosition.totalCostBasisUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        // Total basis across both buckets is conserved.
        assertThat(aWethPosition.totalCostBasisUsd().add(wethPosition.totalCostBasisUsd()))
                .isEqualByComparingTo(new BigDecimal("2664.00"));
    }

    @Test
    void vaultWithdrawWithEmptyPoolStillProducesReallocationNotAcquire() {
        // Confirm the ACQUIRE fallback does NOT fire for VAULT_WITHDRAW — only for
        // PROTOCOL_CUSTODY_WITHDRAW. Scoping guard must remain strict.
        NormalizedTransaction.Flow sharesOut = flow(NormalizedLegRole.TRANSFER, "vUSDC", "-1000");
        NormalizedTransaction.Flow usdcIn = flow(NormalizedLegRole.TRANSFER, "USDC", "1000");
        usdcIn.setUnitPriceUsd(new BigDecimal("1.0"));

        NormalizedTransaction transaction = transaction(NormalizedTransactionType.VAULT_WITHDRAW, sharesOut, usdcIn);
        transaction.setBlockTimestamp(Instant.ofEpochSecond(1700000001));

        String wallet = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
        AssetKey sharesKey = new AssetKey(wallet, NetworkId.ETHEREUM, "vusdc", "vUSDC", "ETHEREUM:vUSDC");
        AssetKey usdcKey = new AssetKey(wallet, NetworkId.ETHEREUM, "usdc", "USDC", "ETHEREUM:USDC");

        when(assetSupport.assetKey(transaction, sharesOut)).thenReturn(sharesKey);
        when(assetSupport.assetKey(transaction, usdcIn)).thenReturn(usdcKey);
        when(assetSupport.assetIdentity(transaction, sharesOut)).thenReturn("ETHEREUM:vUSDC");
        when(assetSupport.assetIdentity(transaction, usdcIn)).thenReturn("ETHEREUM:USDC");

        IndexedFlow outboundIndexed = new IndexedFlow(0, sharesOut);
        IndexedFlow inboundIndexed = new IndexedFlow(1, usdcIn);
        SimpleFamilyCustodySelection selection = new SimpleFamilyCustodySelection(
                List.of(new SimpleFamilyCustodyPair(outboundIndexed, inboundIndexed)),
                Map.of(0, outboundIndexed, 1, inboundIndexed)
        );

        List<AssetLedgerPoint> collected = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("test-universe", collected, Instant.now());
        ReplayExecutionState replayState = new ReplayExecutionState(
                com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan.empty(),
                collector
        );

        handler.applySelected(transaction, selection, replayState);

        // For VAULT_WITHDRAW with empty pool: REALLOCATE_OUT produces no point (position was 0).
        // REALLOCATE_IN also produces no point because carry is $0 and before==after for inbound.
        // No ACQUIRE point must appear.
        assertThat(collected).noneMatch(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE);
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
