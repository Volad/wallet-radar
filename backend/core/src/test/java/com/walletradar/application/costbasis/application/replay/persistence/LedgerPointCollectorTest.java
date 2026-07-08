package com.walletradar.application.costbasis.application.replay.persistence;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPointCollectorTest {

    @Test
    @DisplayName("LP-RECEIPT assetKey symbol produces FAMILY:LP_RECEIPT family identity regardless of marker flow symbol")
    void recordAssignsLpReceiptFamilyWhenAssetKeySymbolIsLpReceipt() {
        AssetKey lpReceiptKey = new AssetKey(
                "wallet-a",
                NetworkId.BASE,
                "0xsome-lp-contract",
                "LP-RECEIPT:BASE:PANCAKESWAP:448475",
                "LP-RECEIPT:BASE:PANCAKESWAP:448475"
        );

        // Marker flow is WETH (the principal asset) — should NOT override the LP-RECEIPT family
        NormalizedTransaction tx = buildTx("tx-lp-1", "WETH");
        NormalizedTransaction.Flow flow = buildFlow("WETH");

        PositionSnapshot before = emptySnapshot();
        PositionState after = stateWithQuantity(lpReceiptKey, "513.47", "1182");

        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("UNIVERSE:lp", points, Instant.now());
        collector.record(tx, flow, 0, lpReceiptKey, before, after, AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:LP_RECEIPT");
        assertThat(points.getFirst().getAssetSymbol()).isEqualTo("LP-RECEIPT:BASE:PANCAKESWAP:448475");
    }

    @Test
    @DisplayName("LP-RECEIPT symbol variant (ETH marker flow) also produces FAMILY:LP_RECEIPT")
    void recordAssignsLpReceiptFamilyWithEthMarkerFlow() {
        AssetKey lpReceiptKey = new AssetKey(
                "wallet-a",
                NetworkId.BASE,
                "0xsome-lp-contract",
                "LP-RECEIPT:BASE:PANCAKESWAP:448475",
                "LP-RECEIPT:BASE:PANCAKESWAP:448475"
        );

        NormalizedTransaction tx = buildTx("tx-lp-2", "ETH");
        NormalizedTransaction.Flow flow = buildFlow("ETH");

        PositionSnapshot before = emptySnapshot();
        PositionState after = stateWithQuantity(lpReceiptKey, "1000", "2300");

        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("UNIVERSE:lp", points, Instant.now());
        collector.record(tx, flow, 0, lpReceiptKey, before, after, AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:LP_RECEIPT");
    }

    @Test
    @DisplayName("WETH assetKey symbol resolves to FAMILY:ETH from symbol map")
    void recordAssignsEthFamilyWhenAssetKeySymbolIsWeth() {
        AssetKey wethKey = new AssetKey(
                "wallet-a",
                NetworkId.BASE,
                "0x4200000000000000000000000000000000000006",
                "WETH",
                "0x4200000000000000000000000000000000000006"
        );

        NormalizedTransaction tx = buildTx("tx-weth-1", "WETH");
        NormalizedTransaction.Flow flow = buildFlow("WETH");

        PositionSnapshot before = emptySnapshot();
        PositionState after = stateWithQuantity(wethKey, "0.546", "1184");

        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("UNIVERSE:eth", points, Instant.now());
        collector.record(tx, flow, 0, wethKey, before, after, AssetLedgerPoint.BasisEffect.ACQUIRE);

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:ETH");
        assertThat(points.getFirst().getAssetSymbol()).isEqualTo("WETH");
    }

    @Test
    @DisplayName("ETH assetKey symbol resolves to FAMILY:ETH")
    void recordAssignsEthFamilyWhenAssetKeySymbolIsEth() {
        AssetKey ethKey = new AssetKey(
                "wallet-a",
                NetworkId.ARBITRUM,
                null,
                "ETH",
                "NATIVE:ARBITRUM"
        );

        NormalizedTransaction tx = buildTx("tx-eth-1", "ETH");
        NormalizedTransaction.Flow flow = buildFlow("ETH");

        PositionSnapshot before = emptySnapshot();
        PositionState after = stateWithQuantity(ethKey, "1.5", "2550");

        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("UNIVERSE:eth", points, Instant.now());
        collector.record(tx, flow, 0, ethKey, before, after, AssetLedgerPoint.BasisEffect.ACQUIRE);

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:ETH");
    }

    @Test
    @DisplayName("record() skips writing when before and after state are identical")
    void recordSkipsWhenStateUnchanged() {
        AssetKey wethKey = new AssetKey("wallet-a", NetworkId.BASE, "0x4200", "WETH", "0x4200");
        NormalizedTransaction tx = buildTx("tx-noop", "WETH");
        NormalizedTransaction.Flow flow = buildFlow("WETH");

        // same qty=0 before and after — sameAs() returns true → record() must skip
        PositionSnapshot before = emptySnapshot();
        PositionState after = new PositionState(wethKey);

        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("UNIVERSE:eth", points, Instant.now());
        collector.record(tx, flow, 0, wethKey, before, after, AssetLedgerPoint.BasisEffect.ACQUIRE);

        assertThat(points).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static NormalizedTransaction buildTx(String id, String symbol) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash("0x" + id);
        tx.setBlockTimestamp(Instant.parse("2025-09-12T10:00:00Z"));
        tx.setTransactionIndex(0);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(BigDecimal.ONE);
        tx.setFlows(List.of(flow));
        return tx;
    }

    private static NormalizedTransaction.Flow buildFlow(String symbol) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(BigDecimal.ONE);
        return flow;
    }

    private static PositionSnapshot emptySnapshot() {
        return PositionSnapshot.mirrorTax(
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                0
        );
    }

    private static PositionState stateWithQuantity(AssetKey key, String qty, String totalCostBasis) {
        PositionState state = new PositionState(key);
        state.setQuantity(new BigDecimal(qty));
        state.setTotalCostBasisUsd(new BigDecimal(totalCostBasis));
        if (new BigDecimal(qty).signum() > 0) {
            state.setPerWalletAvco(new BigDecimal(totalCostBasis).divide(new BigDecimal(qty), java.math.MathContext.DECIMAL128));
        }
        return state;
    }
}
