package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.LpReceiptBasisPoolService;
import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.LpReceiptBasisPoolReplayContext;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.costbasis.domain.LpReceiptBasisPoolRepository;
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

class PositionScopedLpExitReplayHandlerTest {

    private static final String CORR = "lp-position:bsc:pancakeswap:reward-sideflow";
    private static final String UNIVERSE = "GLOBAL";

    @Test
    void shouldIgnoreLpReceiptMarkerOnlyForOutboundOnlyLpEntries() {
        NormalizedTransaction outboundOnly = lpEntry("lp-position:bsc:pancakeswap:1", "-1", null);
        NormalizedTransaction.Flow inboundMarker = flow(
                NormalizedLegRole.TRANSFER, "LP-RECEIPT:bsc:pancakeswap:1", "0xlp", "1");
        outboundOnly.getFlows().add(inboundMarker);

        PositionScopedLpExitReplayHandler handler = handler();
        assertThat(handler.shouldIgnoreLpReceiptMarker(outboundOnly, inboundMarker)).isTrue();

        NormalizedTransaction multiAsset = lpEntry(null, "-1", null);
        NormalizedTransaction.Flow inboundReceipt = flow(
                NormalizedLegRole.TRANSFER, "AAVE GHO/USDT/USDC", "0xfcec3c8d86329defb548202fe1b86ff2188603a8", "1");
        multiAsset.getFlows().add(inboundReceipt);
        assertThat(handler.shouldIgnoreLpReceiptMarker(multiAsset, inboundReceipt)).isFalse();
    }

    @Test
    void pricedSideflowAcquiresStablecoinWithoutConsumingLpReceiptPoolPrincipal() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator =
                new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport,
                flowSupport,
                settlementAllocator,
                poolService,
                keyFactory
        );

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        LpReceiptBasisPool pool = poolService.lookupOrCreate(
                UNIVERSE,
                CORR,
                "wallet-a",
                NetworkId.BSC,
                "0xxyz",
                "XYZ",
                "0xxyz",
                pools,
                dirty,
                Instant.parse("2026-03-25T10:00:00Z")
        );
        poolService.deposit(pool, new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        NormalizedTransaction partialExit = tx(
                "partial",
                NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "USDT", "0x55d398326f99059ff775485246999027b3197955", "5"),
                flow(NormalizedLegRole.TRANSFER, "XYZ", "0xxyz", "40")
        );
        handler.apply(partialExit, state);

        AssetLedgerPoint usdt = points.stream()
                .filter(p -> "USDT".equals(p.getAssetSymbol()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(usdt.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(usdt.getTotalCostBasisAfterUsd()).isEqualByComparingTo("5");
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("60");
    }

    @Test
    void singleAssetExitCarriesCrossAssetBasisFromMultiAssetEntry() {
        String multiCorr = "lp-position:base:pancakeswap:multi";
        String multiUniverse = "MULTI";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator =
                new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport,
                flowSupport,
                settlementAllocator,
                poolService,
                keyFactory
        );

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                multiUniverse,
                multiCorr,
                "wallet-multi",
                NetworkId.BASE,
                "FAMILY:USDC",
                "USDC",
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools,
                dirty,
                entryTs
        );
        poolService.deposit(usdcPool, new BigDecimal("731"), new BigDecimal("731"), BigDecimal.ZERO);

        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                multiUniverse,
                multiCorr,
                "wallet-multi",
                NetworkId.BASE,
                "FAMILY:ETH",
                "WETH",
                "0x4200000000000000000000000000000000000006",
                pools,
                dirty,
                entryTs
        );
        poolService.deposit(wethPool, new BigDecimal("0.05"), new BigDecimal("200"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(multiUniverse, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(multiUniverse, pools, dirty)
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("exit-multi");
        tx.setTxHash("0xmulti");
        tx.setWalletAddress("wallet-multi");
        tx.setNetworkId(NetworkId.BASE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LP_EXIT);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        tx.setCorrelationId(multiCorr);
        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("897"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);
        tx.setFlows(List.of(usdcIn));

        handler.apply(tx, state);

        AssetLedgerPoint usdcReallocate = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow();
        // Same-asset USDC pool ($731 basis) plus cross-asset WETH pool ($200 basis).
        assertThat(usdcReallocate.getTotalCostBasisAfterUsd()).isEqualByComparingTo("931");
        // WETH pool fully drained on a full-proportion exit.
        assertThat(wethPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(wethPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        // USDC pool also fully drained because requested qty exceeded held qty.
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
    }

    private static PositionScopedLpExitReplayHandler handler() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator =
                new ReplaySettlementAllocator(assetSupport, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        return new PositionScopedLpExitReplayHandler(
                assetSupport,
                flowSupport,
                settlementAllocator,
                new LpReceiptBasisPoolService(repo),
                keyFactory
        );
    }

    private static NormalizedTransaction lpEntry(String correlationId, String outboundQty, String inboundQty) {
        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        flows.add(flow(NormalizedLegRole.TRANSFER, "WETH", "0xweth", outboundQty));
        if (inboundQty != null) {
            flows.add(flow(NormalizedLegRole.TRANSFER, "LP-RECEIPT", "0xlp", inboundQty));
        }
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("lp-entry");
        tx.setTxHash("0xlp-entry");
        tx.setWalletAddress("wallet-a");
        tx.setNetworkId(NetworkId.BSC);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        tx.setCorrelationId(correlationId);
        tx.setFlows(flows);
        return tx;
    }

    private static NormalizedTransaction tx(
            String id,
            NormalizedTransactionType type,
            NormalizedTransaction.Flow... flows
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash("0x" + id);
        tx.setWalletAddress("wallet-a");
        tx.setNetworkId(NetworkId.BSC);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        tx.setCorrelationId(CORR);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String symbol,
            String contract,
            String qty
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setPriceSource(PriceSource.UNKNOWN);
        return flow;
    }
}
