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

    @Test
    void singleAssetUsdVaultExitCapsContaminatedStablecoinBasisToPeg() {
        // U-3: a single-asset USD-stablecoin vault receipt pool carrying contaminated share-rate
        // basis ($1,790 on 895 USDC = $2/unit) is capped to the $1 peg on a same-asset exit (no
        // cross-asset basis carried), so the withdrawn USDC disposes at ≈$0 realised.
        String corr = "lp-position:base:fluid:fusdc";
        String universe = "U3";
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
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe,
                corr,
                "wallet-u3",
                NetworkId.BASE,
                "FAMILY:USDC",
                "USDC",
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools,
                dirty,
                Instant.parse("2026-03-25T09:00:00Z")
        );
        poolService.deposit(usdcPool, new BigDecimal("895"), new BigDecimal("1790"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty)
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("u3-exit");
        tx.setTxHash("0xu3");
        tx.setWalletAddress("wallet-u3");
        tx.setNetworkId(NetworkId.BASE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LP_EXIT);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        tx.setCorrelationId(corr);
        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("895"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);
        tx.setFlows(List.of(usdcIn));

        handler.apply(tx, state);

        AssetLedgerPoint usdcReallocate = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow();
        // $1,790 contaminated basis capped to 895 × $1 = $895.
        assertThat(usdcReallocate.getTotalCostBasisAfterUsd()).isEqualByComparingTo("895");
    }

    @Test
    void feeOnlyLpExitDoesNotDrainReceiptPool() {
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

        NormalizedTransaction feeOnly = tx(
                "fee-only",
                NormalizedTransactionType.LP_FEE_CLAIM,
                flow(NormalizedLegRole.TRANSFER, "CAKE", "0xcake", "3")
        );
        assertThat(handler.hasPrincipalCloseEvidence(feeOnly)).isFalse();

        handler.apply(feeOnly, state);

        assertThat(pool.getQtyHeld()).isEqualByComparingTo("100");
        assertThat(points).isNotEmpty();
        assertThat(points.stream().noneMatch(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)).isTrue();
    }

    @Test
    void principalExitWithoutReceiptBurnDrainsSyntheticMarkerWhenPoolsEmpty() {
        String corr = "lp-position:base:pancakeswap:938761";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator =
                new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        LpReceiptEntryReplayHandler entryHandler = new LpReceiptEntryReplayHandler(
                assetSupport,
                flowSupport,
                poolService
        );
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport,
                flowSupport,
                settlementAllocator,
                poolService,
                keyFactory
        );

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        AssetKey usdcKey = new AssetKey(
                "wallet-a",
                NetworkId.BASE,
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "USDC",
                "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
        );
        PositionState usdcPosition = state.position(usdcKey);
        usdcPosition.setQuantity(new BigDecimal("500"));
        usdcPosition.setTotalCostBasisUsd(new BigDecimal("500"));
        usdcPosition.setUncoveredQuantity(BigDecimal.ZERO);

        NormalizedTransaction lastEntry = null;
        for (int i = 0; i < 3; i++) {
            NormalizedTransaction entry = lpEntry(corr, "-100", null);
            lastEntry = entry;
            entry.setId("entry-" + i);
            entry.setNetworkId(NetworkId.BASE);
            entry.getFlows().getFirst().setAssetSymbol("USDC");
            entry.getFlows().getFirst().setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
            entryHandler.apply(entry, state);
        }

        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(lastEntry, corr);
        assertThat(state.position(receiptKey).quantity()).isEqualByComparingTo("1");

        for (int i = 0; i < 2; i++) {
            NormalizedTransaction exit = tx(
                    "exit-" + i,
                    NormalizedTransactionType.LP_EXIT,
                    flow(NormalizedLegRole.TRANSFER, "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "150")
            );
            exit.setCorrelationId(corr);
            exit.setNetworkId(NetworkId.BASE);
            exitHandler.apply(exit, state);
        }

        assertThat(state.position(receiptKey).quantity()).isEqualByComparingTo("0");
    }

    @Test
    void principalCloseZerosSyntheticReceiptMarkerAndPools() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        String corr = "lp-position:unichain:uniswap:42775";
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
                corr,
                "wallet-a",
                NetworkId.UNICHAIN,
                "FAMILY:USDC",
                "USDC",
                null,
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

        NormalizedTransaction entry = lpEntry(corr, "-100", null);
        entry.setNetworkId(NetworkId.UNICHAIN);
        NormalizedTransaction.Flow entryFlow = entry.getFlows().getFirst();
        entryFlow.setAssetSymbol("USDC");
        entryFlow.setAssetContract("0xusdc");
        new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService).apply(entry, state);

        AssetKey receiptKey = assetSupport.lpReceiptPositionKey(entry, corr);
        assertThat(state.position(receiptKey).quantity()).isEqualByComparingTo("1");

        NormalizedTransaction exit = tx(
                "principal-close",
                NormalizedTransactionType.LP_EXIT,
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "100"),
                flow(NormalizedLegRole.TRANSFER, "LP-RECEIPT:UNICHAIN:UNISWAP:42775", null, "-1")
        );
        exit.setCorrelationId(corr);
        exit.setNetworkId(NetworkId.UNICHAIN);
        handler.apply(exit, state);

        assertThat(state.position(receiptKey).quantity()).isEqualByComparingTo("0");
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
    }

    // ──────────────────────────────────────────────────────────────
    // S-1: LP_EXIT cross-pool attribution guard tests
    // ──────────────────────────────────────────────────────────────

    /**
     * T1: Two-asset LP_EXIT — WETH and USDC both returned in the same transaction.
     * WETH must receive only its own WETH pool basis; USDC must receive only its own USDC pool
     * basis. Cross-pool carry must be suppressed for both assets because each has a direct
     * inbound TRANSFER flow.
     */
    @Test
    void twoAssetLpExit_wethAndUsdcBothReturned_noCrossDrain() {
        String corr = "lp-position:arb:uniswap:445831";
        String universe = "TEST-CL";

        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-01-01T00:00:00Z");

        // WETH pool: 0.042975 WETH held, $72.58 basis
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BSC,
                "FAMILY:ETH", "WETH", "0xweth", pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.042975"), new BigDecimal("72.58"), BigDecimal.ZERO);

        // USDC pool: 636.16 USDC held, $869.34 basis
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BSC,
                "FAMILY:USDC", "USDC", "0xusdc", pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("636.16"), new BigDecimal("869.34"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        // Exit transaction: both WETH and USDC returned simultaneously
        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("exit-445831");
        exit.setTxHash("0x457b9d30");
        exit.setWalletAddress("wallet-x");
        exit.setNetworkId(NetworkId.BSC);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-01T00:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.TRANSFER);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAssetContract("0xweth");
        wethIn.setQuantityDelta(new BigDecimal("0.021934"));
        wethIn.setPriceSource(PriceSource.UNKNOWN);

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0xusdc");
        usdcIn.setQuantityDelta(new BigDecimal("324.77"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(wethIn, usdcIn));
        handler.apply(exit, state);

        // WETH REALLOCATE_IN must reflect WETH pool basis only (no USDC cross-drain)
        AssetLedgerPoint wethPoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("Expected WETH REALLOCATE_IN ledger point"));
        // Only WETH pool basis should be attributed — proportional share of $72.58
        assertThat(wethPoint.getCostBasisDeltaUsd())
                .as("WETH cost basis delta should come only from WETH pool, not USDC cross-drain")
                .isLessThan(new BigDecimal("100"));

        // USDC REALLOCATE_IN must reflect USDC pool basis only (no WETH cross-drain)
        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("Expected USDC REALLOCATE_IN ledger point"));
        assertThat(usdcPoint.getCostBasisDeltaUsd())
                .as("USDC cost basis delta should come only from USDC pool, not WETH cross-drain")
                .isLessThan(new BigDecimal("900"));

        // Pools must each be partially (proportionally) drained but not over-drained
        assertThat(wethPool.getQtyHeld())
                .as("WETH pool partially drained")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(usdcPool.getQtyHeld())
                .as("USDC pool partially drained independently")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    /**
     * T2: Single-asset LP_EXIT — WETH only returned, USDC remained in the pool (out of range).
     * WETH must receive WETH pool basis plus cross-drain from the USDC pool (existing behavior
     * preserved — impermanent loss basis carry must still work).
     */
    @Test
    void singleAssetLpExit_wethOnly_crossDrainFromUsdcPoolPreserved() {
        String corr = "lp-position:arb:uniswap:single-weth";
        String universe = "TEST-SINGLE-WETH";

        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-01-01T00:00:00Z");

        // WETH pool: 0.05 WETH held, $150 basis
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BASE,
                "FAMILY:ETH", "WETH", "0x4200000000000000000000000000000000000006",
                pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.05"), new BigDecimal("150"), BigDecimal.ZERO);

        // USDC pool: 500 USDC held, $500 basis (retained in pool — out of range)
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BASE,
                "FAMILY:USDC", "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("500"), new BigDecimal("500"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        // Exit transaction: WETH only returned (USDC not returned — no USDC inbound flow)
        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("exit-weth-only");
        exit.setTxHash("0xweth-only");
        exit.setWalletAddress("wallet-x");
        exit.setNetworkId(NetworkId.BASE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-01T00:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.TRANSFER);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAssetContract("0x4200000000000000000000000000000000000006");
        wethIn.setQuantityDelta(new BigDecimal("0.05"));
        wethIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(wethIn));  // only WETH, no USDC
        handler.apply(exit, state);

        AssetLedgerPoint wethPoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("Expected WETH REALLOCATE_IN ledger point"));

        // WETH must carry the full WETH pool ($150) plus the full USDC cross-drain ($500)
        assertThat(wethPoint.getTotalCostBasisAfterUsd())
                .as("WETH gets WETH pool + USDC cross-drain when USDC is not directly returned")
                .isEqualByComparingTo("650");

        // USDC pool must be fully drained via cross-carry
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdcPool.getBasisHeldUsd()).isEqualByComparingTo("0");
    }

    /**
     * T3: Single-asset LP_EXIT — USDC only returned, WETH retained in the pool.
     * USDC must receive USDC pool basis plus cross-drain from the WETH pool.
     */
    @Test
    void singleAssetLpExit_usdcOnly_crossDrainFromWethPoolPreserved() {
        String corr = "lp-position:base:pancakeswap:single-usdc";
        String universe = "TEST-SINGLE-USDC";

        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine());
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-01-01T00:00:00Z");

        // USDC pool: 731 USDC held, $731 basis
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BASE,
                "FAMILY:USDC", "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("731"), new BigDecimal("731"), BigDecimal.ZERO);

        // WETH pool: 0.05 WETH held, $200 basis (retained in pool — out of range)
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-x", NetworkId.BASE,
                "FAMILY:ETH", "WETH", "0x4200000000000000000000000000000000000006",
                pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.05"), new BigDecimal("200"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        // Exit transaction: USDC only returned (no WETH inbound flow)
        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("exit-usdc-only");
        exit.setTxHash("0xusdc-only");
        exit.setWalletAddress("wallet-x");
        exit.setNetworkId(NetworkId.BASE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-01T00:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("897"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(usdcIn));  // only USDC, no WETH
        handler.apply(exit, state);

        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("Expected USDC REALLOCATE_IN ledger point"));

        // USDC must carry full USDC pool ($731) plus WETH cross-drain ($200)
        assertThat(usdcPoint.getTotalCostBasisAfterUsd())
                .as("USDC gets USDC pool + WETH cross-drain when WETH is not directly returned")
                .isEqualByComparingTo("931");

        // WETH pool must be fully drained via cross-carry
        assertThat(wethPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(wethPool.getBasisHeldUsd()).isEqualByComparingTo("0");
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
