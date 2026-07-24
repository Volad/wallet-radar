package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R7 — LFJ / Trader Joe Liquidity Book (ERC-1155 LBToken) multi-cycle exit conservation.
 *
 * <p>Regression anchor: AVALANCHE AUSD/USDC pair {@code 0x8573f98175d816d520248b5facf40d309b1c9cee}
 * (correlation {@code lp-position:avalanche:lfj:0x8573…}). The wallet made 5 addLiquidity entries and
 * 4 removeLiquidity exits, interleaved chronologically, into ONE pair-level receipt basis pool. The
 * verification audit measured $845.21 of returned principal booked as zero-net market {@code ACQUIRE}
 * (net destruction) because the ERC-1155 receipt was tracked with a synthetic qty=1 marker that
 * treated every partial exit as a full close. This test replays the real 9-tx sequence and asserts
 * Option-B conservation: no principal ACQUIRE beyond the ≈$2.63 accrued fee, combined basis conserved.
 */
class LfjLiquidityBookExitReplayTest {

    private static final String CORR = "lp-position:avalanche:lfj:0x8573f98175d816d520248b5facf40d309b1c9cee";
    private static final String UNIVERSE = "LFJ";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String AUSD = "0x00000000efe302beaa2b3e6e1b18d08d69a9012a";
    private static final String USDC = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";
    private static final String RECEIPT_SYMBOL =
            "LP-RECEIPT:AVALANCHE:LFJ:0x8573f98175d816d520248b5facf40d309b1c9cee";

    @Test
    void lfjMultiCycleExitsConserveCombinedBasisNoPrincipalDestruction() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        LpReceiptEntryReplayHandler entryHandler = new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty));

        // Seed generous stablecoin spot positions at the $1 peg so entries have basis to reallocate.
        seedSpot(state, AUSD, "AUSD", "3000");
        seedSpot(state, USDC, "USDC", "8000");

        int t = 0;
        entryHandler.apply(entry(t++, "-499.200024", "-500.967914"), state);   // tx1 2025-10-01
        exitHandler.apply(exit(t++, "103.522229", "896.606205"), state);       // tx2 2025-10-02
        entryHandler.apply(entry(t++, "-253.555337", "-746.594079"), state);   // tx3 2025-10-02
        entryHandler.apply(entry(t++, "-11.060533", "-32.407179"), state);     // tx4 2025-10-02
        exitHandler.apply(exit(t++, "223.179186", "820.598434"), state);       // tx5 2025-10-06
        entryHandler.apply(entry(t++, "-715.134502", "-2097.678925"), state);  // tx6 2025-12-01
        exitHandler.apply(exit(t++, "646.172775", "2166.628953"), state);      // tx7 2025-12-01
        entryHandler.apply(entry(t++, "-706.179842", "-2106.628956"), state);  // tx8 2025-12-01
        exitHandler.apply(exit(t++, "1045.204182", "1770.121851"), state);     // tx9 2025-12-12

        BigDecimal principalAcquireTax = BigDecimal.ZERO;
        BigDecimal principalReallocateInTax = BigDecimal.ZERO;
        for (AssetLedgerPoint p : points) {
            if (!"AUSD".equals(p.getAssetSymbol()) && !"USDC".equals(p.getAssetSymbol())) {
                continue;
            }
            if (p.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE) {
                principalAcquireTax = principalAcquireTax.add(p.getCostBasisDeltaUsd(), java.math.MathContext.DECIMAL128);
            } else if (p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN) {
                principalReallocateInTax = principalReallocateInTax.add(
                        p.getCostBasisDeltaUsd(), java.math.MathContext.DECIMAL128);
            }
        }

        // Option-B acceptance: returned principal is return-of-capital (REALLOCATE_IN carrying pooled
        // basis), NOT a zero-net market ACQUIRE. The pre-fix defect booked $845.21 across 4 legs as
        // ACQUIRE (net destruction); the accrued net gain on this stable pair is only ≈$2.63.
        assertThat(principalAcquireTax)
                .as("principal booked as market ACQUIRE (basis destruction) — was $845.21 before D1/R7")
                .isLessThan(new BigDecimal("10"));
        assertThat(principalReallocateInTax)
                .as("returned principal must be carried as REALLOCATE_IN (return of capital)")
                .isGreaterThan(new BigDecimal("7000"));

        // All pools fully drained (both lanes) at the end of the lifecycle — no stranded basis.
        for (LpReceiptBasisPool pool : pools.values()) {
            assertThat(pool.getBasisHeldUsd()).as("pool tax residual").isEqualByComparingTo("0");
            assertThat(pool.getNetBasisHeldUsd()).as("pool net residual").isEqualByComparingTo("0");
        }
    }

    private static void seedSpot(ReplayExecutionState state, String contract, String symbol, String qty) {
        AssetKey key = new AssetKey(WALLET, NetworkId.AVALANCHE, contract, symbol, contract);
        PositionState pos = state.position(key);
        pos.setQuantity(new BigDecimal(qty));
        pos.setTotalCostBasisUsd(new BigDecimal(qty));
        pos.setNetTotalCostBasisUsd(new BigDecimal(qty));
        pos.setUncoveredQuantity(BigDecimal.ZERO);
        pos.setPerWalletAvco(BigDecimal.ONE);
        pos.setPerWalletNetAvco(BigDecimal.ONE);
    }

    private static NormalizedTransaction entry(int idx, String ausdDelta, String usdcDelta) {
        NormalizedTransaction tx = base(idx, NormalizedTransactionType.LP_ENTRY);
        tx.setFlows(List.of(
                principal(AUSD, "AUSD", ausdDelta),
                principal(USDC, "USDC", usdcDelta),
                receipt("1")
        ));
        return tx;
    }

    private static NormalizedTransaction exit(int idx, String ausdDelta, String usdcDelta) {
        NormalizedTransaction tx = base(idx, NormalizedTransactionType.LP_EXIT);
        tx.setFlows(List.of(
                principal(AUSD, "AUSD", ausdDelta),
                principal(USDC, "USDC", usdcDelta),
                receipt("-1")
        ));
        return tx;
    }

    private static NormalizedTransaction base(int idx, NormalizedTransactionType type) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("lfj-" + idx);
        tx.setTxHash("0xlfj" + idx);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(Instant.parse("2025-10-01T00:00:00Z").plusSeconds(idx * 86400L));
        tx.setCorrelationId(CORR);
        return tx;
    }

    private static NormalizedTransaction.Flow principal(String contract, String symbol, String delta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(delta));
        flow.setPriceSource(PriceSource.STABLECOIN);
        flow.setUnitPriceUsd(BigDecimal.ONE);
        return flow;
    }

    private static NormalizedTransaction.Flow receipt(String delta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(RECEIPT_SYMBOL);
        flow.setQuantityDelta(new BigDecimal(delta));
        flow.setPriceSource(PriceSource.UNKNOWN);
        return flow;
    }
}
