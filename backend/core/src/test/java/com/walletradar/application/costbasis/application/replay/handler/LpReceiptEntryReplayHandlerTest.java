package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LpReceiptEntryReplayHandlerTest {

    @Mock
    private LpReceiptBasisPoolRepository lpReceiptBasisPoolRepository;

    private LpReceiptEntryReplayHandler handler;
    private ReplayExecutionState replayState;

    @BeforeEach
    void setUp() {
        lenient().when(lpReceiptBasisPoolRepository.findByUniverseId("u1")).thenReturn(List.of());
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        handler = new LpReceiptEntryReplayHandler(
                assetSupport,
                flowSupport,
                new LpReceiptBasisPoolService(lpReceiptBasisPoolRepository)
        );
        LedgerPointCollector collector = new LedgerPointCollector("u1", new ArrayList<>(), Instant.now());
        LpReceiptBasisPoolReplayContext poolContext = new LpReceiptBasisPoolReplayContext(
                "u1",
                new LinkedHashMap<>(),
                new HashSet<>()
        );
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                collector,
                null,
                null,
                poolContext
        );
    }

    @Test
    void movesOutboundBasisIntoLpReceiptPool() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        NormalizedTransaction tx = lpEntry("0x1", "-0.5");
        var flow = tx.getFlows().getFirst();
        var assetKey = assetSupport.assetKey(tx, flow);
        PositionState before = replayState.position(assetKey);
        before.setQuantity(new BigDecimal("1"));
        before.setTotalCostBasisUsd(new BigDecimal("2000"));
        before.setUncoveredQuantity(BigDecimal.ZERO);
        before.setPerWalletAvco(new BigDecimal("2000"));

        handler.apply(tx, replayState);

        assertThat(before.quantity()).isEqualByComparingTo("0.5");
        assertThat(replayState.lpReceiptBasisPoolContext().pools()).hasSize(1);
        var pool = replayState.lpReceiptBasisPoolContext().pools().values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0.5");
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo("1000");
    }

    @Test
    void synthesizesSingleNftReceiptMarkerWithPoolBasis() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        NormalizedTransaction tx = lpEntry("0x1", "-0.5");
        var flow = tx.getFlows().getFirst();
        var assetKey = assetSupport.assetKey(tx, flow);
        PositionState before = replayState.position(assetKey);
        before.setQuantity(new BigDecimal("1"));
        before.setTotalCostBasisUsd(new BigDecimal("2000"));
        before.setUncoveredQuantity(BigDecimal.ZERO);
        before.setPerWalletAvco(new BigDecimal("2000"));

        handler.apply(tx, replayState);

        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(tx, tx.getCorrelationId());
        PositionState receipt = replayState.position(receiptKey);
        assertThat(receipt.quantity()).isEqualByComparingTo("1");
        assertThat(receipt.totalCostBasisUsd()).isEqualByComparingTo("1000");
    }

    @Test
    void recognizesLpPositionCorrelationEntry() {
        NormalizedTransaction tx = lpEntry("0x1", "-0.1");
        assertThat(handler.isLpReceiptEntry(tx)).isTrue();
    }

    @Test
    void multiAssetLpEntryCreatesPoolPerOutboundFamily() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        NormalizedTransaction tx = multiAssetLpEntry("0x1", "-0.05", "-731.03");

        var wethFlow = tx.getFlows().get(0);
        var wethKey = assetSupport.assetKey(tx, wethFlow);
        PositionState wethPosition = replayState.position(wethKey);
        wethPosition.setQuantity(new BigDecimal("1"));
        wethPosition.setTotalCostBasisUsd(new BigDecimal("2000"));
        wethPosition.setUncoveredQuantity(BigDecimal.ZERO);
        wethPosition.setPerWalletAvco(new BigDecimal("2000"));

        var usdcFlow = tx.getFlows().get(1);
        var usdcKey = assetSupport.assetKey(tx, usdcFlow);
        PositionState usdcPosition = replayState.position(usdcKey);
        usdcPosition.setQuantity(new BigDecimal("2000"));
        usdcPosition.setTotalCostBasisUsd(new BigDecimal("2000"));
        usdcPosition.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPosition.setPerWalletAvco(BigDecimal.ONE);

        assertThat(handler.isLpReceiptEntry(tx)).isTrue();
        handler.apply(tx, replayState);

        var pools = replayState.lpReceiptBasisPoolContext().pools();
        assertThat(pools).hasSize(2);
        BigDecimal totalBasis = pools.values().stream()
                .map(p -> p.getBasisHeldUsd() == null ? BigDecimal.ZERO : p.getBasisHeldUsd())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalBasis).isEqualByComparingTo("831.03");
        assertThat(pools.values()).allMatch(p -> "lp-position:base:pancakeswap:99".equals(p.getLpCorrelationId()));
    }

    // --- hasOnlyOutboundPrincipalFlows tests (T1–T5) ---

    @Test
    void t1_lpEntryWithRouterRefundIsRecognisedAsReceiptPoolEntry() {
        // Uniswap router refunds 0.0158 ETH of an 0.6158 ETH deposit → net -0.6 ETH outbound
        NormalizedTransaction tx = lpEntryWithRefund("0x1", "-0.615779357568571248", "+0.015779357623930477", "-801.45");
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isTrue();
        assertThat(handler.isLpReceiptEntry(tx)).isTrue();
    }

    @Test
    void t2_lpEntryWithDustRefundIsRecognisedAsReceiptPoolEntry() {
        // Dust rounding refund: +6.85E-16 ETH on top of -0.130613 ETH deposit
        NormalizedTransaction tx = lpEntryWithDustRefund("0x1", "-0.13061309055749152", "6.85E-16", "-400.088613");
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isTrue();
        assertThat(handler.isLpReceiptEntry(tx)).isTrue();
    }

    @Test
    void t3_standardLpEntryWithNoRefundRemainsTrue() {
        // Standard LP_ENTRY: only outbound flows — behaviour unchanged
        NormalizedTransaction tx = lpEntry("0x1", "-0.5");
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isTrue();
    }

    @Test
    void t4_curveBalancerShapeWithNetInboundAssetReturnsFalse() {
        // Curve/Balancer: user deposits 100 USDC, receives 0.05 ETH back (net ETH > 0)
        NormalizedTransaction tx = curveStyleLpEntry("0x1");
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isFalse();
        assertThat(handler.isLpReceiptEntry(tx)).isFalse();
    }

    @Test
    void t5_refundExceedsDepositForAssetReturnsFalse() {
        // Refund larger than deposit for same asset → net outbound becomes net inbound → must reject
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setWalletAddress("0x1");
        tx.setNetworkId(NetworkId.BASE);
        tx.setBlockTimestamp(Instant.now());
        NormalizedTransaction.Flow outEth = new NormalizedTransaction.Flow();
        outEth.setRole(NormalizedLegRole.TRANSFER);
        outEth.setAssetSymbol("ETH");
        outEth.setQuantityDelta(new BigDecimal("-0.1"));
        NormalizedTransaction.Flow refundEth = new NormalizedTransaction.Flow();
        refundEth.setRole(NormalizedLegRole.TRANSFER);
        refundEth.setAssetSymbol("ETH");
        refundEth.setQuantityDelta(new BigDecimal("0.5")); // refund > deposit
        tx.setFlows(List.of(outEth, refundEth));
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isFalse();
    }

    @Test
    void t6_pendleLptInboundTreatedAsReceiptNotPrincipal() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setWalletAddress("0x1");
        tx.setNetworkId(NetworkId.BASE);
        tx.setBlockTimestamp(Instant.now());
        NormalizedTransaction.Flow outCmEth = new NormalizedTransaction.Flow();
        outCmEth.setRole(NormalizedLegRole.TRANSFER);
        outCmEth.setAssetSymbol("cmETH");
        outCmEth.setQuantityDelta(new BigDecimal("-0.861423922419174928"));
        NormalizedTransaction.Flow inLpt = new NormalizedTransaction.Flow();
        inLpt.setRole(NormalizedLegRole.TRANSFER);
        inLpt.setAssetSymbol("PENDLE-LPT");
        inLpt.setQuantityDelta(new BigDecimal("0.445041029858104302"));
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("MNT");
        fee.setQuantityDelta(new BigDecimal("-0.042"));
        tx.setFlows(new ArrayList<>(List.of(outCmEth, inLpt, fee)));
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isTrue();
    }

    @Test
    void t7_eqbPendleLptInboundTreatedAsReceiptNotPrincipal() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setWalletAddress("0x1");
        tx.setNetworkId(NetworkId.BASE);
        tx.setBlockTimestamp(Instant.now());
        NormalizedTransaction.Flow outCmEth = new NormalizedTransaction.Flow();
        outCmEth.setRole(NormalizedLegRole.TRANSFER);
        outCmEth.setAssetSymbol("cmETH");
        outCmEth.setQuantityDelta(new BigDecimal("-0.861"));
        NormalizedTransaction.Flow inEqbLpt = new NormalizedTransaction.Flow();
        inEqbLpt.setRole(NormalizedLegRole.TRANSFER);
        inEqbLpt.setAssetSymbol("eqbPENDLE-LPT");
        inEqbLpt.setQuantityDelta(new BigDecimal("0.445"));
        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("MNT");
        fee.setQuantityDelta(new BigDecimal("-0.042"));
        tx.setFlows(new ArrayList<>(List.of(outCmEth, inEqbLpt, fee)));
        assertThat(LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(tx)).isTrue();
    }

    private static NormalizedTransaction lpEntryWithRefund(
            String wallet,
            String ethOut,
            String ethRefund,
            String usdtOut
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-lp-refund");
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T12:00:00Z"));

        NormalizedTransaction.Flow outEth = new NormalizedTransaction.Flow();
        outEth.setRole(NormalizedLegRole.TRANSFER);
        outEth.setAssetSymbol("ETH");
        outEth.setQuantityDelta(new BigDecimal(ethOut));

        NormalizedTransaction.Flow refundEth = new NormalizedTransaction.Flow();
        refundEth.setRole(NormalizedLegRole.TRANSFER);
        refundEth.setAssetSymbol("ETH");
        refundEth.setQuantityDelta(new BigDecimal(ethRefund));

        NormalizedTransaction.Flow outUsdt = new NormalizedTransaction.Flow();
        outUsdt.setRole(NormalizedLegRole.TRANSFER);
        outUsdt.setAssetSymbol("USDT");
        outUsdt.setQuantityDelta(new BigDecimal(usdtOut));

        NormalizedTransaction.Flow fee = new NormalizedTransaction.Flow();
        fee.setRole(NormalizedLegRole.FEE);
        fee.setAssetSymbol("ETH");
        fee.setQuantityDelta(new BigDecimal("-0.0001"));

        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setRole(NormalizedLegRole.TRANSFER);
        receipt.setAssetSymbol("LP-RECEIPT:BASE:PANCAKESWAP:99");
        receipt.setQuantityDelta(BigDecimal.ONE);

        tx.setFlows(List.of(outEth, refundEth, outUsdt, fee, receipt));
        return tx;
    }

    private static NormalizedTransaction lpEntryWithDustRefund(
            String wallet,
            String ethOut,
            String ethDustRefund,
            String usdcOut
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-lp-dust");
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T12:00:00Z"));

        NormalizedTransaction.Flow outEth = new NormalizedTransaction.Flow();
        outEth.setRole(NormalizedLegRole.TRANSFER);
        outEth.setAssetSymbol("ETH");
        outEth.setQuantityDelta(new BigDecimal(ethOut));

        NormalizedTransaction.Flow dustRefund = new NormalizedTransaction.Flow();
        dustRefund.setRole(NormalizedLegRole.TRANSFER);
        dustRefund.setAssetSymbol("ETH");
        dustRefund.setQuantityDelta(new BigDecimal(ethDustRefund));

        NormalizedTransaction.Flow outUsdc = new NormalizedTransaction.Flow();
        outUsdc.setRole(NormalizedLegRole.TRANSFER);
        outUsdc.setAssetSymbol("USDC");
        outUsdc.setQuantityDelta(new BigDecimal(usdcOut));

        NormalizedTransaction.Flow receipt = new NormalizedTransaction.Flow();
        receipt.setRole(NormalizedLegRole.TRANSFER);
        receipt.setAssetSymbol("LP-RECEIPT:BASE:PANCAKESWAP:99");
        receipt.setQuantityDelta(BigDecimal.ONE);

        tx.setFlows(List.of(outEth, dustRefund, outUsdc, receipt));
        return tx;
    }

    private static NormalizedTransaction curveStyleLpEntry(String wallet) {
        // User deposits USDC and receives ETH back (different asset returned → Curve/Balancer shape)
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-curve");
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T12:00:00Z"));

        NormalizedTransaction.Flow outUsdc = new NormalizedTransaction.Flow();
        outUsdc.setRole(NormalizedLegRole.TRANSFER);
        outUsdc.setAssetSymbol("USDC");
        outUsdc.setQuantityDelta(new BigDecimal("-100"));

        NormalizedTransaction.Flow inEth = new NormalizedTransaction.Flow();
        inEth.setRole(NormalizedLegRole.TRANSFER);
        inEth.setAssetSymbol("ETH");
        inEth.setQuantityDelta(new BigDecimal("0.05")); // net ETH is positive → reject

        tx.setFlows(List.of(outUsdc, inEth));
        return tx;
    }

    private static NormalizedTransaction lpEntry(String wallet, String wethQty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-lp-1");
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T12:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("WETH");
        flow.setQuantityDelta(new BigDecimal(wethQty));
        tx.setFlows(List.of(flow));
        return tx;
    }

    private static NormalizedTransaction multiAssetLpEntry(
            String wallet,
            String wethQty,
            String usdcQty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-lp-multi");
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:base:pancakeswap:99");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T12:00:00Z"));
        NormalizedTransaction.Flow weth = new NormalizedTransaction.Flow();
        weth.setRole(NormalizedLegRole.TRANSFER);
        weth.setAssetSymbol("WETH");
        weth.setQuantityDelta(new BigDecimal(wethQty));
        NormalizedTransaction.Flow usdc = new NormalizedTransaction.Flow();
        usdc.setRole(NormalizedLegRole.TRANSFER);
        usdc.setAssetSymbol("USDC");
        usdc.setQuantityDelta(new BigDecimal(usdcQty));
        tx.setFlows(List.of(weth, usdc));
        return tx;
    }
}
