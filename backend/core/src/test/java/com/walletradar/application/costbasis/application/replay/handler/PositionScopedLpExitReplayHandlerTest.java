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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
                // No contract: symbol-based stablecoin check fires in unit-test env
                // (NetworkStablecoinContracts is unbound, so contract lookup returns empty).
                flow(NormalizedLegRole.TRANSFER, "USDT", null, "5"),
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
    void dualTokenExitCarriesUsdcPegCapSurplusToVolatileSibling() {
        // R2: dual-token WETH+USDC exit. USDC pool basis $593.92 exceeds qty×$1 = $322.23, so
        // $271.69 surplus is injected into the WETH pool. WETH absorbs combined remainder:
        // $1,460 (own pool) + $271.69 (surplus) = $1,731.69. USDC AVCO stays at $1.000.
        String corr = "lp-position:base:pancakeswap:72791605";
        String universe = "R2";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // WETH pool: 0.157 qty held, $1460 tax basis
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-a", NetworkId.BSC,
                "FAMILY:ETH", "WETH", "0x4200000000000000000000000000000000000006",
                pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.157"), new BigDecimal("1460"), BigDecimal.ZERO);

        // USDC pool: 322.23 qty held, $593.92 tax basis (above $1 peg for the qty)
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-a", NetworkId.BSC,
                "FAMILY:USDC", "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("322.23"), new BigDecimal("593.92"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty)
        );

        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("exit-dual");
        exit.setTxHash("0x72791605exit");
        exit.setWalletAddress("wallet-a");
        exit.setNetworkId(NetworkId.BSC);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT_FINAL);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        // USDC is first, WETH second — stable before volatile
        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("322.23"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);

        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.TRANSFER);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAssetContract("0x4200000000000000000000000000000000000006");
        wethIn.setQuantityDelta(new BigDecimal("0.157"));
        wethIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(usdcIn, wethIn));
        handler.apply(exit, state);

        // USDC: peg-capped at $1/unit → $322.23 basis (not $593.92)
        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()) && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(usdcPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("322.23");

        // WETH: own pool ($1460) + USDC peg-cap surplus ($271.69) = $1731.69
        AssetLedgerPoint wethPoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol()) && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(wethPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("1731.69");

        // All pools fully drained
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdcPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(wethPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(wethPool.getBasisHeldUsd()).isEqualByComparingTo("0");
    }

    @Test
    void singleAssetUsdVaultExitCapsContaminatedStablecoinBasisToPeg() {
        // U-3: a single-asset USD-stablecoin vault receipt pool carrying contaminated share-rate
        // basis ($1,790 on 895 USDC = $2/unit) is capped to the $1 peg on a same-asset exit (no
        // cross-asset basis carried), so the withdrawn USDC disposes at ≈$0 realised.
        String corr = "lp-position:base:fluid:fusdc";
        String universe = "U3";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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

    /**
     * RC-6 regression guard: the Equilibria {@code zapOutV3SingleToken} LP_EXIT must keep restoring the
     * underlying (cmETH) from the {@code pendle-lp:*} receipt basis pool via REALLOCATE_IN. The RC-6
     * fix makes the upstream STAKING_DEPOSIT a non-realizing wrap that leaves this pool untouched, so
     * the pool-backed exit path must continue to work unchanged.
     */
    @Test
    void pendleZapOutLpExitStillReallocatesUnderlyingFromReceiptPool() {
        String corr = "pendle-lp:mantle:pendle-lpt:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
        String universe = "PENDLE";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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

        // zapOutV3SingleToken: eqbPENDLE-LPT net-zero unwrap → +0.862 cmETH underlying returned.
        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("pendle-zapout");
        exit.setTxHash("0xf7f8");
        exit.setWalletAddress("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        exit.setNetworkId(NetworkId.MANTLE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-26T11:00:00Z"));
        exit.setCorrelationId(corr);
        NormalizedTransaction.Flow cmethIn = new NormalizedTransaction.Flow();
        cmethIn.setRole(NormalizedLegRole.TRANSFER);
        cmethIn.setAssetSymbol("cmETH");
        cmethIn.setAssetContract("0xcmeth");
        cmethIn.setQuantityDelta(new BigDecimal("0.862"));
        cmethIn.setPriceSource(PriceSource.UNKNOWN);
        exit.setFlows(List.of(cmethIn));

        // Basis parked at LP entry lives in the pendle-lp receipt pool keyed by the underlying identity.
        String underlyingIdentity = assetSupport.continuityIdentity(exit, cmethIn);
        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        LpReceiptBasisPool pool = poolService.lookupOrCreate(
                universe,
                corr,
                "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
                NetworkId.MANTLE,
                underlyingIdentity,
                "cmETH",
                "0xcmeth",
                pools,
                dirty,
                Instant.parse("2026-03-25T09:00:00Z")
        );
        poolService.deposit(pool, new BigDecimal("0.862"), new BigDecimal("3380.66"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty)
        );

        handler.apply(exit, state);

        AssetLedgerPoint cmethReallocate = points.stream()
                .filter(p -> "CMETH".equalsIgnoreCase(p.getAssetSymbol()))
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("Expected cmETH REALLOCATE_IN from pendle-lp pool"));
        assertThat(cmethReallocate.getTotalCostBasisAfterUsd()).isEqualByComparingTo("3380.66");
        // Pool fully drained by the exit.
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo("0");
    }

    /**
     * R6a all-stablecoin per-lane cap: an all-stable Balancer V3 exit (GHO/USDT/USDC boosted pool)
     * has NO volatile absorber. The default same-asset peg-cap would clamp each stable leg to
     * qty×$1 and strand the above-$1 compounded yield (here $5.85). Because each stablecoin drains
     * its OWN per-asset pool, Σ carried == combinedBasis exactly when the real pool basis is carried,
     * so the cap is skipped for the {@code :balancerv3:} correlation. Evidence anchor (AVALANCHE):
     * boosted-stable BPT {@code 0xfcec3c8d…}.
     */
    @Test
    void allStableBalancerV3ExitCarriesRealPoolBasisWithoutPegCap() {
        String corr = "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String universe = "R6A";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // USDC pool: 100 qty, $103 tax basis ($1.03/unit compounded), $101 net basis.
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-r6a", NetworkId.AVALANCHE,
                "FAMILY:USDC", "USDC", "0xusdc", pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("100"), new BigDecimal("103"),
                new BigDecimal("101"), BigDecimal.ZERO);

        // USDT pool: 100 qty, $102.85 tax basis, $101 net basis.
        LpReceiptBasisPool usdtPool = poolService.lookupOrCreate(
                universe, corr, "wallet-r6a", NetworkId.AVALANCHE,
                "FAMILY:USDT", "USDT", "0xusdt", pools, dirty, entryTs);
        poolService.deposit(usdtPool, new BigDecimal("100"), new BigDecimal("102.85"),
                new BigDecimal("101"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("r6a-allstable-exit");
        exit.setTxHash("0xr6aallstable");
        exit.setWalletAddress("wallet-r6a");
        exit.setNetworkId(NetworkId.AVALANCHE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT_FINAL);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0xusdc");
        usdcIn.setQuantityDelta(new BigDecimal("100"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);

        NormalizedTransaction.Flow usdtIn = new NormalizedTransaction.Flow();
        usdtIn.setRole(NormalizedLegRole.TRANSFER);
        usdtIn.setAssetSymbol("USDT");
        usdtIn.setAssetContract("0xusdt");
        usdtIn.setQuantityDelta(new BigDecimal("100"));
        usdtIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(usdcIn, usdtIn));
        handler.apply(exit, state);

        // USDC carries its FULL pool basis ($103), NOT the $100 peg cap.
        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(usdcPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("103");

        // USDT carries its FULL pool basis ($102.85), NOT the $100 peg cap.
        AssetLedgerPoint usdtPoint = points.stream()
                .filter(p -> "USDT".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(usdtPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("102.85");

        // Σ carried == combined pool basis exactly (no fabrication, no destruction).
        assertThat(usdcPoint.getTotalCostBasisAfterUsd().add(usdtPoint.getTotalCostBasisAfterUsd()))
                .isEqualByComparingTo("205.85");

        // Both pools fully drained.
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdtPool.getQtyHeld()).isEqualByComparingTo("0");
    }

    @Test
    void d1_dualTokenExitVolatileLegFirst_strandedStableSurplusCarriedNotAcquired() {
        // D1/D2 (R2 Option-B2): dual-token WETH+USDC full exit where the VOLATILE leg is processed
        // BEFORE the stable leg. The old inject-to-sibling path relied on stable-before-volatile
        // ordering; here WETH drains first (pool → 0), then the USDC peg-cap surplus is injected into
        // the already-drained WETH pool and would be destroyed at drain. The D2 net-lane carry sweeps
        // that leftover onto WETH as REALLOCATE_IN so the combined LP basis is fully conserved and NO
        // principal is booked as a market-priced ACQUIRE.
        String corr = "lp-position:base:uniswap:924461";
        String universe = "D1";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // WETH pool: 0.1 qty, $150 tax / $150 net.
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-d1", NetworkId.BASE,
                "FAMILY:ETH", "WETH", "0x4200000000000000000000000000000000000006",
                pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.1"), new BigDecimal("150"),
                new BigDecimal("150"), BigDecimal.ZERO);

        // USDC pool: 100 qty, $250 tax / $250 net ($2.50/unit compounded above peg).
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-d1", NetworkId.BASE,
                "FAMILY:USDC", "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("100"), new BigDecimal("250"),
                new BigDecimal("250"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("d1-exit");
        exit.setTxHash("0x924461exit");
        exit.setWalletAddress("wallet-d1");
        exit.setNetworkId(NetworkId.BASE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT_FINAL);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        // WETH FIRST (volatile), USDC second (stable) — the ordering the old inject path could not handle.
        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.TRANSFER);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAssetContract("0x4200000000000000000000000000000000000006");
        wethIn.setQuantityDelta(new BigDecimal("0.1"));
        wethIn.setPriceSource(PriceSource.UNKNOWN);

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("100"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);

        exit.setFlows(List.of(wethIn, usdcIn));
        handler.apply(exit, state);

        // No principal leg may be booked as a market-priced ACQUIRE (the D1 defect).
        assertThat(points.stream()
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE)
                .anyMatch(p -> "WETH".equals(p.getAssetSymbol()) || "USDC".equals(p.getAssetSymbol())))
                .as("no principal ACQUIRE (return-of-capital must be REALLOCATE_IN)")
                .isFalse();

        // USDC peg-capped to $1/unit → $100. WETH absorbs the remainder: $150 own + $150 USDC surplus = $300.
        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(usdcPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("100");

        AssetLedgerPoint wethPoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(wethPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("300");

        // Combined LP basis fully conserved ($400 = $150 + $250) and both lanes obey net ≤ tax.
        assertThat(usdcPoint.getTotalCostBasisAfterUsd().add(wethPoint.getTotalCostBasisAfterUsd()))
                .isEqualByComparingTo("400");
        assertThat(wethPoint.getNetTotalCostBasisAfterUsd())
                .isLessThanOrEqualTo(wethPoint.getTotalCostBasisAfterUsd());
        assertThat(usdcPoint.getNetTotalCostBasisAfterUsd())
                .isLessThanOrEqualTo(usdcPoint.getTotalCostBasisAfterUsd());

        // Pools fully zeroed on close (tax, net, qty).
        assertThat(wethPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(wethPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(wethPool.getNetBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdcPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(usdcPool.getNetBasisHeldUsd()).isEqualByComparingTo("0");
    }

    @Test
    void d2_fullExitCarriesNetLaneOntoExitAssetBeforeZeroingPool() {
        // D2 (R3 net-lane carry): a terminal exit leaves residual pool basis (returned qty < pool
        // qty on a final close). The pre-fix drain zeroed the tax lane AND destroyed the net lane.
        // The fix carries BOTH lanes onto the exit asset first, THEN zeroes — so netBasisHeldUsd is
        // conserved onto the returned principal (net ≤ tax preserved).
        String corr = "lp-position:optimism:uniswap:2984825";
        String universe = "D2";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // WETH pool: 0.2 qty, $300 tax / $240 net (reward-discounted below tax).
        LpReceiptBasisPool wethPool = poolService.lookupOrCreate(
                universe, corr, "wallet-d2", NetworkId.OPTIMISM,
                "FAMILY:ETH", "WETH", "0x4200000000000000000000000000000000000006",
                pools, dirty, entryTs);
        poolService.deposit(wethPool, new BigDecimal("0.2"), new BigDecimal("300"),
                new BigDecimal("240"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        // LP_EXIT_FINAL returns only half the pooled qty (0.1 of 0.2) — the position is closed on
        // chain (final), so the remaining pool basis must be carried, not stranded.
        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("d2-exit");
        exit.setTxHash("0x2984825exit");
        exit.setWalletAddress("wallet-d2");
        exit.setNetworkId(NetworkId.OPTIMISM);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT_FINAL);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow wethIn = new NormalizedTransaction.Flow();
        wethIn.setRole(NormalizedLegRole.TRANSFER);
        wethIn.setAssetSymbol("WETH");
        wethIn.setAssetContract("0x4200000000000000000000000000000000000006");
        wethIn.setQuantityDelta(new BigDecimal("0.1"));
        wethIn.setPriceSource(PriceSource.UNKNOWN);
        exit.setFlows(List.of(wethIn));
        handler.apply(exit, state);

        // The final WETH point must carry BOTH lanes: tax $300 and net $240 (nothing destroyed).
        AssetLedgerPoint wethPoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(wethPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("300");
        assertThat(wethPoint.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("240");
        assertThat(wethPoint.getNetTotalCostBasisAfterUsd())
                .isLessThanOrEqualTo(wethPoint.getTotalCostBasisAfterUsd());

        // Pool fully zeroed AFTER carry — net lane conserved, not destroyed.
        assertThat(wethPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(wethPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(wethPool.getNetBasisHeldUsd()).isEqualByComparingTo("0");
    }

    @Test
    void d3_stablecoinExitNetLaneNeverFabricatedAbovePooledNet() {
        // D3 (net-lane peg-cap clamp): the pre-fix restore floored the NET lane up to qty×$1
        // (pegFlooredStablecoinCarryBasis) while the TAX lane stayed at the below-face pool basis —
        // producing net > tax (the 450450 / 2984825 fabrications). Here the USDC pool net ($80) is
        // materially below peg (< 0.90/unit) so the old floor would inflate it to $100 > tax $95.
        // The fix carries the pooled net exactly (no floor) and clamps 0 ≤ net ≤ tax.
        String corr = "lp-position:base:aerodrome:450450";
        String universe = "D3";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // USDC pool: 100 qty, $95 tax ($0.95/unit — above the 0.90 floor threshold, NOT floored),
        // $80 net ($0.80/unit — below 0.90 → the old net floor would inflate to $100).
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-d3", NetworkId.BASE,
                "FAMILY:USDC", "USDC", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("80"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("d3-exit");
        exit.setTxHash("0x450450exit");
        exit.setWalletAddress("wallet-d3");
        exit.setNetworkId(NetworkId.BASE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        NormalizedTransaction.Flow usdcIn = new NormalizedTransaction.Flow();
        usdcIn.setRole(NormalizedLegRole.TRANSFER);
        usdcIn.setAssetSymbol("USDC");
        usdcIn.setAssetContract("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        usdcIn.setQuantityDelta(new BigDecimal("100"));
        usdcIn.setPriceSource(PriceSource.UNKNOWN);
        exit.setFlows(List.of(usdcIn));
        handler.apply(exit, state);

        AssetLedgerPoint usdcPoint = points.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .reduce((a, b) -> b).orElseThrow();
        // Tax lane unchanged ($0.95/unit ≥ 0.90 floor → $95). Net lane carried as pooled ($80),
        // NOT fabricated up to the $100 face value → net ≤ tax holds.
        assertThat(usdcPoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("95");
        assertThat(usdcPoint.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("80");
        assertThat(usdcPoint.getNetTotalCostBasisAfterUsd())
                .as("net lane must never exceed tax lane (no fabrication)")
                .isLessThanOrEqualTo(usdcPoint.getTotalCostBasisAfterUsd());
    }

    @Test
    void d4_lpFeeIncomeLegBooksFmvInTaxLaneAndZeroInNetLane() {
        // D4 (fee-income pricing): a priced LP_FEE_INCOME leg must book fee income at FMV in the tax
        // (market) lane and $0 in the net lane (zero-cost acquisition). Pre-fix the passed cost was
        // hard-coded to $0, so FMV was dropped even after the leg was priced.
        String corr = "lp-position:base:uniswap:fee-income";
        String universe = "D4";
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        NormalizedTransaction exit = new NormalizedTransaction();
        exit.setId("d4-exit");
        exit.setTxHash("0xfeeincome");
        exit.setWalletAddress("wallet-d4");
        exit.setNetworkId(NetworkId.BASE);
        exit.setSource(NormalizedTransactionSource.ON_CHAIN);
        exit.setType(NormalizedTransactionType.LP_EXIT);
        exit.setStatus(NormalizedTransactionStatus.CONFIRMED);
        exit.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        exit.setCorrelationId(corr);

        // Priced WETH fee-income leg: 0.01 WETH @ $2,500 → FMV $25.
        NormalizedTransaction.Flow feeIn = new NormalizedTransaction.Flow();
        feeIn.setRole(NormalizedLegRole.LP_FEE_INCOME);
        feeIn.setAssetSymbol("WETH");
        feeIn.setAssetContract("0x4200000000000000000000000000000000000006");
        feeIn.setQuantityDelta(new BigDecimal("0.01"));
        feeIn.setUnitPriceUsd(new BigDecimal("2500"));
        feeIn.setPriceSource(PriceSource.COINGECKO);
        exit.setFlows(List.of(feeIn));
        handler.apply(exit, state);

        AssetLedgerPoint feePoint = points.stream()
                .filter(p -> "WETH".equals(p.getAssetSymbol())
                        && p.getBasisEffect() == AssetLedgerPoint.BasisEffect.ACQUIRE)
                .reduce((a, b) -> b).orElseThrow();
        // Tax lane = FMV $25, net lane = $0 (zero-cost LP_FEE_CLAIM).
        assertThat(feePoint.getTotalCostBasisAfterUsd()).isEqualByComparingTo("25");
        assertThat(feePoint.getNetTotalCostBasisAfterUsd()).isEqualByComparingTo("0");
    }

    /**
     * R6a (M1 replay defect) regression: partial Balancer V3 BPT burn while a portion of the BPT
     * remains staked, followed by a final burn of the un-staked residual.
     *
     * <p>Lifecycle modelled on the boosted-stable {@code 0xfcec3c8d…} pool (Avalanche): an all-stable
     * GHO/USDT/USDC BPT is entered (pools + LP-RECEIPT marker seeded), most of the BPT is burned in a
     * partial exit ({@code 0xe84d…}) while ~2% stays staked in Aura, and the remaining 2% is later
     * unstaked and burned ({@code 0xdf5c…}).</p>
     *
     * <p>Pre-fix defect: the partial burn drained the per-asset pools by returned-underlying quantity,
     * which — because a rebalanced stable exit returns MORE of one leg than that leg's own pool held —
     * swept the sibling pool leftover too and released the FULL combined basis ($2,214 on the real
     * position) while the LP-RECEIPT marker only decremented proportionally ($2,169). The pools were
     * thus emptied and the final burn self-assigned {@code UNKNOWN} at face (the +$43.37 fabrication).</p>
     *
     * <p>Post-fix: the partial burn releases only {@code burnFraction × combinedBasis} (proportional to
     * the BPT actually burned), leaving the still-staked portion's basis in the pools so the final burn
     * draws it as {@code REALLOCATE_IN} — no over-return at burn #1, no {@code UNKNOWN} at the final
     * burn, correlation Σ net ≈ $0, and all receipt pools drain to 0.</p>
     */
    @Test
    void partialBalancerBptBurnWhileStakedReturnsProportionalBasisAndFinalBurnDrawsResidual() {
        String corr = "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String receiptSymbol = "LP-RECEIPT:AVALANCHE:BALANCERV3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
        String universe = "R6A-PARTIAL";
        BigDecimal eps = new BigDecimal("0.0001");

        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        PositionScopedLpExitReplayHandler handler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        Instant entryTs = Instant.parse("2026-03-25T09:00:00Z");

        // Entry composition parked at LP_ENTRY: USDC 60 ($60) + USDT 40 ($40) = combined $100 for 100 BPT.
        LpReceiptBasisPool usdcPool = poolService.lookupOrCreate(
                universe, corr, "wallet-r6a", NetworkId.AVALANCHE,
                "FAMILY:USDC", "USDC", "0xusdc", pools, dirty, entryTs);
        poolService.deposit(usdcPool, new BigDecimal("60"), new BigDecimal("60"), BigDecimal.ZERO);
        LpReceiptBasisPool usdtPool = poolService.lookupOrCreate(
                universe, corr, "wallet-r6a", NetworkId.AVALANCHE,
                "FAMILY:USDT", "USDT", "0xusdt", pools, dirty, entryTs);
        poolService.deposit(usdtPool, new BigDecimal("40"), new BigDecimal("40"), BigDecimal.ZERO);

        List<AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(universe, points, Instant.now()),
                null, null,
                new LpReceiptBasisPoolReplayContext(universe, pools, dirty));

        // Seed the LP-RECEIPT (BPT) marker position with the full 100 BPT / $100 combined basis, keyed
        // as the Balancer V3 burn flow resolves it (assetKey off the explicit receipt burn leg).
        NormalizedTransaction partialBurn = new NormalizedTransaction();
        partialBurn.setId("burn1");
        partialBurn.setTxHash("0xe84d");
        partialBurn.setWalletAddress("wallet-r6a");
        partialBurn.setNetworkId(NetworkId.AVALANCHE);
        partialBurn.setSource(NormalizedTransactionSource.ON_CHAIN);
        partialBurn.setType(NormalizedTransactionType.LP_EXIT);
        partialBurn.setStatus(NormalizedTransactionStatus.CONFIRMED);
        partialBurn.setBlockTimestamp(Instant.parse("2026-03-25T10:00:00Z"));
        partialBurn.setCorrelationId(corr);
        NormalizedTransaction.Flow burn1Receipt = flow(NormalizedLegRole.TRANSFER, receiptSymbol, null, "-98");
        AssetKey receiptKey = assetSupport.assetKey(partialBurn, burn1Receipt);
        PositionState receiptPos = state.position(receiptKey);
        receiptPos.setQuantity(new BigDecimal("100"));
        receiptPos.setTotalCostBasisUsd(new BigDecimal("100"));
        receiptPos.setNetTotalCostBasisUsd(new BigDecimal("100"));

        // ---- Burn #1 (PARTIAL): burn 98 of 100 BPT; returns a REBALANCED stable mix (USDT 48 > its
        // own $40 pool) that historically over-drained the whole combined basis. ----
        partialBurn.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "50"),
                flow(NormalizedLegRole.TRANSFER, "USDT", "0xusdt", "48"),
                burn1Receipt));
        handler.apply(partialBurn, state);
        List<AssetLedgerPoint> burn1Points = new ArrayList<>(points);

        // Proportional return: burn #1 releases only burnFraction(0.98) × $100 = $98, NOT the full $100.
        BigDecimal burn1Returned = burn1Points.stream()
                .filter(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.REALLOCATE_IN)
                .map(AssetLedgerPoint::getCostBasisDeltaUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(burn1Returned)
                .as("partial burn returns proportional basis (no over-return of the still-staked portion)")
                .isCloseTo(new BigDecimal("98"), org.assertj.core.api.Assertions.within(eps));

        // The still-staked ~2% survives in the receipt pools for the later burn.
        BigDecimal residualPoolBasis = usdcPool.getBasisHeldUsd().add(usdtPool.getBasisHeldUsd());
        assertThat(residualPoolBasis)
                .as("un-burned (still-staked) fraction retained in the receipt pools")
                .isCloseTo(new BigDecimal("2"), org.assertj.core.api.Assertions.within(eps));
        // Marker decremented proportionally (mirrors the pools).
        assertThat(receiptPos.quantity()).isEqualByComparingTo("2");
        assertThat(receiptPos.totalCostBasisUsd())
                .isCloseTo(new BigDecimal("2"), org.assertj.core.api.Assertions.within(eps));

        // ---- Final burn: the residual 2 BPT is unstaked and burned (full close). ----
        NormalizedTransaction finalBurn = new NormalizedTransaction();
        finalBurn.setId("burn2");
        finalBurn.setTxHash("0xdf5c");
        finalBurn.setWalletAddress("wallet-r6a");
        finalBurn.setNetworkId(NetworkId.AVALANCHE);
        finalBurn.setSource(NormalizedTransactionSource.ON_CHAIN);
        finalBurn.setType(NormalizedTransactionType.LP_EXIT);
        finalBurn.setStatus(NormalizedTransactionStatus.CONFIRMED);
        finalBurn.setBlockTimestamp(Instant.parse("2026-03-25T12:00:00Z"));
        finalBurn.setCorrelationId(corr);
        finalBurn.setFlows(List.of(
                flow(NormalizedLegRole.TRANSFER, "USDC", "0xusdc", "1"),
                flow(NormalizedLegRole.TRANSFER, "USDT", "0xusdt", "1"),
                flow(NormalizedLegRole.TRANSFER, receiptSymbol, null, "-2")));
        handler.apply(finalBurn, state);
        List<AssetLedgerPoint> finalBurnPoints = points.subList(burn1Points.size(), points.size());

        // The final burn draws the residual as REALLOCATE_IN from the pool — NOT UNKNOWN at face.
        List<AssetLedgerPoint> finalStablePoints = finalBurnPoints.stream()
                .filter(p -> "USDC".equals(p.getAssetSymbol()) || "USDT".equals(p.getAssetSymbol()))
                .toList();
        assertThat(finalStablePoints)
                .as("final burn stables must be return-of-capital, not fabricated income")
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getBasisEffect())
                        .isEqualTo(AssetLedgerPoint.BasisEffect.REALLOCATE_IN));
        BigDecimal burn2Returned = finalStablePoints.stream()
                .map(AssetLedgerPoint::getCostBasisDeltaUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(burn2Returned)
                .as("final burn returns the surviving ~2% residual")
                .isCloseTo(new BigDecimal("2"), org.assertj.core.api.Assertions.within(eps));

        // Combined basis fully conserved across both burns (Σ ≈ combined $100 → no fabrication).
        assertThat(burn1Returned.add(burn2Returned))
                .isCloseTo(new BigDecimal("100"), org.assertj.core.api.Assertions.within(eps));

        // All receipt pools for the correlation drain to 0/0 and the marker is fully closed.
        assertThat(usdcPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdcPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(usdtPool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(usdtPool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(receiptPos.quantity()).isEqualByComparingTo("0");
        // No fabricated income anywhere in the lifecycle.
        assertThat(points).noneMatch(p -> p.getBasisEffect() == AssetLedgerPoint.BasisEffect.UNKNOWN);
    }

    private static PositionScopedLpExitReplayHandler handler() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
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
