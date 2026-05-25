package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository;
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
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
