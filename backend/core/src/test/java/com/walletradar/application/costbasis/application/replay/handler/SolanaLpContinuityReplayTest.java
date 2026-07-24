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
import com.walletradar.application.costbasis.domain.LpReceiptBasisPool;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolKey;
import com.walletradar.application.costbasis.domain.LpReceiptBasisPoolRepository;
import com.walletradar.application.normalization.pipeline.solana.SolanaNormalizedTransactionBuilder;
import com.walletradar.application.normalization.pipeline.solana.SolanaProgramIds;
import com.walletradar.application.normalization.pipeline.solana.SolanaTransactionClassifier;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end RC-S-LP continuity: a Meteora DLMM (NFT-based) LP_ENTRY parks the deposited SOL basis
 * into a position-scoped {@code lp_receipt_basis_pools} bucket, and the later LP_EXIT restores that
 * exact basis to the returned SOL — proving entry↔exit basis continuity through the shared EVM
 * receipt-pool machinery, keyed only by the deterministic position PDA in the Helius payload.
 */
class SolanaLpContinuityReplayTest {

    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String POSITION = "H7wY3yb9LfJYv98yxfyqpPeco3ezKFE5n8VQKRcooe9w";
    private static final String POOL = "CgqwPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXaqY";
    private static final String UNIVERSE = "GLOBAL";
    private static final BigDecimal SOL_QTY = new BigDecimal("1.15788447");
    private static final BigDecimal SOL_BASIS = new BigDecimal("200");

    private final SolanaNormalizedTransactionBuilder builder = new SolanaNormalizedTransactionBuilder(
            new SolanaTransactionClassifier(),
            Mockito.mock(AccountingUniverseService.class)
    );

    @Test
    @DisplayName("Meteora DLMM SOL basis is carried from entry into the pool and restored on exit")
    void dlmmEntryExitCarriesSolBasisThroughPositionPool() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        // --- ENTRY ---
        NormalizedTransaction entry = builder.build(entryRaw(), Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(entry.getCorrelationId()).isEqualTo("lp-position:solana:meteora-dlmm:" + POSITION);
        assertThat(entryHandler.isLpReceiptEntry(entry)).isTrue();

        NormalizedTransaction.Flow entryPrincipal = entry.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        AssetKey solKey = assetSupport.assetKey(entry, entryPrincipal);
        PositionState solPosition = state.position(solKey);
        solPosition.setQuantity(SOL_QTY);
        solPosition.setTotalCostBasisUsd(SOL_BASIS);
        solPosition.setNetTotalCostBasisUsd(SOL_BASIS);
        solPosition.setUncoveredQuantity(BigDecimal.ZERO);
        solPosition.setPerWalletAvco(SOL_BASIS.divide(SOL_QTY, java.math.MathContext.DECIMAL128));

        entryHandler.apply(entry, state);

        assertThat(solPosition.quantity()).isEqualByComparingTo("0");
        assertThat(pools).hasSize(1);
        LpReceiptBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo(SOL_QTY);
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo(SOL_BASIS);

        // --- EXIT (same position PDA → same correlation → same pool) ---
        NormalizedTransaction exit = builder.build(exitRaw(), Instant.parse("2026-02-10T00:00:00Z"));
        assertThat(exit.getCorrelationId()).isEqualTo(entry.getCorrelationId());
        assertThat(exitHandler.isPositionScopedLpExit(exit)).isTrue();

        exitHandler.apply(exit, state);

        assertThat(solPosition.quantity()).isEqualByComparingTo(SOL_QTY);
        assertThat(solPosition.totalCostBasisUsd()).isEqualByComparingTo(SOL_BASIS);
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
    }

    private static final String RAYDIUM_NFT_ACCOUNT = "6ZRCB7AAqGre6c72PRz3MHLC73VMYvJ8bi9KHf1HFpNk";
    private static final String RAYDIUM_POOL_VAULT = "zHGN3Kh1miQSuehUWD1TPYTxkCkUrtT8foNxtASsiKJ";

    @Test
    @DisplayName("Raydium CLMM SOL basis is carried from entry into the position pool and restored on exit")
    void raydiumClmmEntryExitCarriesSolBasisThroughPositionPool() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        // --- ENTRY (single-sided SOL add: 1 net-out, 0 net-in → LP_ENTRY) ---
        NormalizedTransaction entry = builder.build(raydiumEntryRaw(), Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(entry.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(entry.getCorrelationId()).isEqualTo("lp-position:solana:raydium-clmm:" + RAYDIUM_NFT_ACCOUNT);
        assertThat(entryHandler.isLpReceiptEntry(entry)).isTrue();

        NormalizedTransaction.Flow entryPrincipal = entry.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        AssetKey solKey = assetSupport.assetKey(entry, entryPrincipal);
        PositionState solPosition = state.position(solKey);
        solPosition.setQuantity(SOL_QTY);
        solPosition.setTotalCostBasisUsd(SOL_BASIS);
        solPosition.setNetTotalCostBasisUsd(SOL_BASIS);
        solPosition.setUncoveredQuantity(BigDecimal.ZERO);
        solPosition.setPerWalletAvco(SOL_BASIS.divide(SOL_QTY, java.math.MathContext.DECIMAL128));

        entryHandler.apply(entry, state);

        assertThat(solPosition.quantity()).isEqualByComparingTo("0");
        assertThat(pools).hasSize(1);
        LpReceiptBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo(SOL_QTY);
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo(SOL_BASIS);

        // --- EXIT (same NFT account → same correlation → same pool) ---
        NormalizedTransaction exit = builder.build(raydiumExitRaw(), Instant.parse("2026-03-10T00:00:00Z"));
        assertThat(exit.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);
        assertThat(exit.getCorrelationId()).isEqualTo(entry.getCorrelationId());
        assertThat(exitHandler.isPositionScopedLpExit(exit)).isTrue();

        exitHandler.apply(exit, state);

        assertThat(solPosition.quantity()).isEqualByComparingTo(SOL_QTY);
        assertThat(solPosition.totalCostBasisUsd()).isEqualByComparingTo(SOL_BASIS);
        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("RC-S-LP-CLOSE (A): a Raydium CLMM full close drains the residual SOL basis pool when the "
            + "position returns a different asset ratio than deposited")
    void raydiumClmmFullCloseDrainsResidualBasisPool() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        // ENTRY: 1.15788447 SOL deposited at $200 basis.
        NormalizedTransaction entry = builder.build(raydiumEntryRaw(), Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(entry.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        NormalizedTransaction.Flow entryPrincipal = entry.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        AssetKey solKey = assetSupport.assetKey(entry, entryPrincipal);
        PositionState solPosition = state.position(solKey);
        solPosition.setQuantity(SOL_QTY);
        solPosition.setTotalCostBasisUsd(SOL_BASIS);
        solPosition.setNetTotalCostBasisUsd(SOL_BASIS);
        solPosition.setUncoveredQuantity(BigDecimal.ZERO);
        solPosition.setPerWalletAvco(SOL_BASIS.divide(SOL_QTY, java.math.MathContext.DECIMAL128));
        entryHandler.apply(entry, state);
        LpReceiptBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo(SOL_QTY);

        // FINAL EXIT: only 0.5 SOL returned, position NFT account closed (rent reclaimed) →
        // LP_EXIT_FINAL. The 0.657... SOL residual must be written off, not left "held".
        NormalizedTransaction exit = builder.build(
                raydiumExitRaw("0.5", true), Instant.parse("2026-03-10T00:00:00Z"));
        assertThat(exit.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_FINAL);
        assertThat(exit.getCorrelationId()).isEqualTo(entry.getCorrelationId());

        exitHandler.apply(exit, state);

        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo("0");
        assertThat(pool.getUncoveredQtyHeld()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("RC-S-LP-CLOSE: a Raydium CLMM PARTIAL remove (position still open) preserves the residual "
            + "basis pool — only the terminal close drains it")
    void raydiumClmmPartialExitPreservesResidualBasisPool() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        NormalizedTransaction entry = builder.build(raydiumEntryRaw(), Instant.parse("2026-03-01T00:00:00Z"));
        NormalizedTransaction.Flow entryPrincipal = entry.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        AssetKey solKey = assetSupport.assetKey(entry, entryPrincipal);
        PositionState solPosition = state.position(solKey);
        solPosition.setQuantity(SOL_QTY);
        solPosition.setTotalCostBasisUsd(SOL_BASIS);
        solPosition.setNetTotalCostBasisUsd(SOL_BASIS);
        solPosition.setUncoveredQuantity(BigDecimal.ZERO);
        solPosition.setPerWalletAvco(SOL_BASIS.divide(SOL_QTY, java.math.MathContext.DECIMAL128));
        entryHandler.apply(entry, state);
        LpReceiptBasisPool pool = pools.values().iterator().next();

        // PARTIAL EXIT: 0.5 SOL returned, position NFT account NOT closed → stays LP_EXIT.
        NormalizedTransaction exit = builder.build(
                raydiumExitRaw("0.5", false), Instant.parse("2026-03-05T00:00:00Z"));
        assertThat(exit.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT);

        exitHandler.apply(exit, state);

        // Residual basis stays parked in the pool until the real close.
        assertThat(pool.getQtyHeld()).isEqualByComparingTo(SOL_QTY.subtract(new BigDecimal("0.5")));
        assertThat(pool.getBasisHeldUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("RC-S-LP-CLOSE (B): a Meteora DLMM full close writes off principal that was never "
            + "returned (drains the residual pool to zero)")
    void meteoraDlmmFullCloseWritesOffUnreturnedPrincipal() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        NormalizedTransaction entry = builder.build(entryRaw(), Instant.parse("2026-02-01T00:00:00Z"));
        NormalizedTransaction.Flow entryPrincipal = entry.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.TRANSFER).findFirst().orElseThrow();
        AssetKey solKey = assetSupport.assetKey(entry, entryPrincipal);
        PositionState solPosition = state.position(solKey);
        solPosition.setQuantity(SOL_QTY);
        solPosition.setTotalCostBasisUsd(SOL_BASIS);
        solPosition.setNetTotalCostBasisUsd(SOL_BASIS);
        solPosition.setUncoveredQuantity(BigDecimal.ZERO);
        solPosition.setPerWalletAvco(SOL_BASIS.divide(SOL_QTY, java.math.MathContext.DECIMAL128));
        entryHandler.apply(entry, state);
        LpReceiptBasisPool pool = pools.values().iterator().next();
        assertThat(pool.getQtyHeld()).isEqualByComparingTo(SOL_QTY);

        // FINAL EXIT: only 0.05 SOL returned (most principal lost to IL), DLMM position PDA closed →
        // LP_EXIT_FINAL. The ~1.1 SOL residual basis is written off as realized LP PnL.
        NormalizedTransaction exit = builder.build(
                dlmmExitRaw("0.05", true), Instant.parse("2026-02-10T00:00:00Z"));
        assertThat(exit.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_FINAL);
        assertThat(exit.getCorrelationId()).isEqualTo(entry.getCorrelationId());

        exitHandler.apply(exit, state);

        assertThat(pool.getQtyHeld()).isEqualByComparingTo("0");
        assertThat(pool.getBasisHeldUsd()).isEqualByComparingTo("0");
    }

    // --- ADR-081 (C1) auditor fix: Meteora DAMM fungible-MLP entry→exit burns the receipt and
    // REALLOCATE_INs the carried basis onto the returned underlying (mirrors the DLMM path). ---

    private static final String DAMM_POOL = "5yuefgbJJpmFNK2iiYbLSpv1aZXq7F9AUKkZKErTYCvs";
    private static final String DAMM_USER_POOL_LP = "8dLpPLSFfht89pF5RSKGUUMFj5zRxoUt4861w2SkXbZq";
    private static final String MLP_MINT = "MLPzZ9AUKkZKErTYCvs5yuefgbJJpmFNK2iiYbLSpv1a";
    private static final String MSOL_MINT = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So";
    private static final BigDecimal DAMM_SOL_QTY = new BigDecimal("1.0");
    private static final BigDecimal DAMM_MSOL_QTY = new BigDecimal("0.9");
    private static final BigDecimal DAMM_MLP_QTY = new BigDecimal("0.3096");
    private static final BigDecimal DAMM_SOL_BASIS = new BigDecimal("150");
    private static final BigDecimal DAMM_MSOL_BASIS = new BigDecimal("100");

    @Test
    @DisplayName("ADR-081 (C1) auditor: Meteora DAMM LP_ENTRY→LP_EXIT_FINAL burns the MLP receipt "
            + "(net MLP → 0) and REALLOCATE_INs the carried basis onto returned SOL/mSOL (never market)")
    void dammEntryExitBurnsMlpReceiptAndReallocatesBasisOntoUnderlying() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));
        ReplaySettlementAllocator settlementAllocator = new ReplaySettlementAllocator(assetSupport, flowSupport);
        LpReceiptBasisPoolRepository repo = mock(LpReceiptBasisPoolRepository.class);
        when(repo.findByUniverseId(anyString())).thenReturn(List.of());
        LpReceiptBasisPoolService poolService = new LpReceiptBasisPoolService(repo);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);

        LpReceiptEntryReplayHandler entryHandler =
                new LpReceiptEntryReplayHandler(assetSupport, flowSupport, poolService);
        PositionScopedLpExitReplayHandler exitHandler = new PositionScopedLpExitReplayHandler(
                assetSupport, flowSupport, settlementAllocator, poolService, keyFactory);

        LinkedHashMap<LpReceiptBasisPoolKey, LpReceiptBasisPool> pools = new LinkedHashMap<>();
        HashSet<LpReceiptBasisPoolKey> dirty = new HashSet<>();
        List<com.walletradar.application.costbasis.domain.AssetLedgerPoint> points = new ArrayList<>();
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, points, Instant.now()),
                null,
                null,
                new LpReceiptBasisPoolReplayContext(UNIVERSE, pools, dirty)
        );

        // --- ENTRY: deposit SOL + mSOL, receive the fungible MLP receipt (flagged lpReceipt). ---
        NormalizedTransaction entry = builder.build(dammEntryRaw(), Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(entry.getType()).isEqualTo(NormalizedTransactionType.LP_ENTRY);
        assertThat(entry.getCorrelationId())
                .isEqualTo("lp-position:solana:meteora-damm:" + DAMM_POOL + ":" + WALLET);
        assertThat(entryHandler.isLpReceiptEntry(entry)).isTrue();

        NormalizedTransaction.Flow solFlow = dammLeg(entry, SolanaProgramIds.WSOL_MINT);
        NormalizedTransaction.Flow msolFlow = dammLeg(entry, MSOL_MINT);
        NormalizedTransaction.Flow mlpEntryFlow = dammLeg(entry, MLP_MINT);
        AssetKey solKey = assetSupport.assetKey(entry, solFlow);
        AssetKey msolKey = assetSupport.assetKey(entry, msolFlow);
        AssetKey mlpKey = assetSupport.assetKey(entry, mlpEntryFlow);
        seedPosition(state.position(solKey), DAMM_SOL_QTY, DAMM_SOL_BASIS);
        seedPosition(state.position(msolKey), DAMM_MSOL_QTY, DAMM_MSOL_BASIS);

        entryHandler.apply(entry, state);

        // Underlying basis moved into the position pools; the MLP receipt is synthesized holding the
        // combined carried basis and stamped FAMILY:LP_RECEIPT (durable flag route).
        assertThat(state.position(solKey).quantity()).isEqualByComparingTo("0");
        assertThat(state.position(msolKey).quantity()).isEqualByComparingTo("0");
        assertThat(state.position(mlpKey).quantity()).isEqualByComparingTo(DAMM_MLP_QTY);
        assertThat(state.position(mlpKey).totalCostBasisUsd())
                .isEqualByComparingTo(DAMM_SOL_BASIS.add(DAMM_MSOL_BASIS));
        assertThat(points).filteredOn(p -> "MLP".equals(p.getAssetSymbol()))
                .allMatch(p -> "FAMILY:LP_RECEIPT".equals(p.getAccountingFamilyIdentity()));

        // --- EXIT (terminal): return SOL + mSOL, burn the MLP receipt. userPoolLp rent reclaimed. ---
        NormalizedTransaction exit = builder.build(dammExitRaw(), Instant.parse("2026-04-10T00:00:00Z"));
        assertThat(exit.getType()).isEqualTo(NormalizedTransactionType.LP_EXIT_FINAL);
        assertThat(exit.getCorrelationId()).isEqualTo(entry.getCorrelationId());
        assertThat(exitHandler.isPositionScopedLpExit(exit)).isTrue();

        int pointsBeforeExit = points.size();
        exitHandler.apply(exit, state);

        // Net MLP → 0: the receipt is burned, the final MLP ledger point closes the position at zero.
        assertThat(state.position(mlpKey).quantity()).isEqualByComparingTo("0");
        assertThat(state.position(mlpKey).totalCostBasisUsd()).isEqualByComparingTo("0");
        com.walletradar.application.costbasis.domain.AssetLedgerPoint lastMlpPoint = points.stream()
                .filter(p -> "MLP".equals(p.getAssetSymbol()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(lastMlpPoint.getQuantityAfter()).isEqualByComparingTo("0");

        // The carried basis is REALLOCATE_IN onto the returned underlying (never credited at market).
        // Basis is restored from the pool (not re-priced), within DECIMAL128 avco round-trip epsilon.
        BigDecimal eps = new BigDecimal("0.0001");
        assertThat(state.position(solKey).quantity()).isEqualByComparingTo(DAMM_SOL_QTY);
        assertThat(state.position(solKey).totalCostBasisUsd()).isCloseTo(DAMM_SOL_BASIS, within(eps));
        assertThat(state.position(msolKey).quantity()).isEqualByComparingTo(DAMM_MSOL_QTY);
        assertThat(state.position(msolKey).totalCostBasisUsd()).isCloseTo(DAMM_MSOL_BASIS, within(eps));
        List<com.walletradar.application.costbasis.domain.AssetLedgerPoint> exitPoints =
                points.subList(pointsBeforeExit, points.size());
        assertThat(exitPoints).filteredOn(p -> SolanaProgramIds.WSOL_MINT.equals(p.getAssetContract()))
                .allMatch(p -> p.getBasisEffect()
                        == com.walletradar.application.costbasis.domain.AssetLedgerPoint.BasisEffect.REALLOCATE_IN);

        // The MLP burn is a basis-neutral REALLOCATE_OUT — the receipt itself realizes $0 P&L.
        assertThat(exitPoints).filteredOn(p -> "MLP".equals(p.getAssetSymbol()))
                .isNotEmpty()
                .allMatch(p -> p.getBasisEffect()
                        == com.walletradar.application.costbasis.domain.AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                        && (p.getRealisedPnlDeltaUsd() == null || p.getRealisedPnlDeltaUsd().signum() == 0)
                        && "FAMILY:LP_RECEIPT".equals(p.getAccountingFamilyIdentity()));

        // Pools fully drained after the terminal close.
        assertThat(pools.values()).allMatch(p -> p.getQtyHeld().signum() == 0);
    }

    private static void seedPosition(PositionState position, BigDecimal qty, BigDecimal basis) {
        position.setQuantity(qty);
        position.setTotalCostBasisUsd(basis);
        position.setNetTotalCostBasisUsd(basis);
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setPerWalletAvco(basis.divide(qty, java.math.MathContext.DECIMAL128));
        position.setPerWalletNetAvco(basis.divide(qty, java.math.MathContext.DECIMAL128));
    }

    private static NormalizedTransaction.Flow dammLeg(NormalizedTransaction tx, String mint) {
        return tx.getFlows().stream()
                .filter(f -> mint.equals(f.getAssetContract()))
                .findFirst()
                .orElseThrow();
    }

    private static Document dammLiquidityInstruction() {
        // addBalanceLiquidity / removeBalanceLiquidity: [pool, lpMint, userPoolLp, ...] (damm-v1-sdk IDL).
        return new Document("programId", SolanaProgramIds.METEORA_DYNAMIC_AMM).append("accounts", List.of(
                DAMM_POOL, MLP_MINT, DAMM_USER_POOL_LP, "aVaultLp", "bVaultLp", "aVault", "bVault",
                "aTokenVault", "bTokenVault", "aVaultLpMint", "bVaultLpMint", WALLET,
                SolanaProgramIds.TOKEN_PROGRAM, SolanaProgramIds.METEORA_VAULT));
    }

    private static RawTransaction dammEntryRaw() {
        Document parsed = new Document("type", "ADD_LIQUIDITY")
                .append("fee", 5_000L)
                .append("timestamp", 1_774_915_200L)
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        new Document("fromUserAccount", WALLET).append("toUserAccount", DAMM_POOL)
                                .append("mint", SolanaProgramIds.WSOL_MINT).append("symbol", "SOL")
                                .append("tokenAmount", DAMM_SOL_QTY.doubleValue()),
                        new Document("fromUserAccount", WALLET).append("toUserAccount", DAMM_POOL)
                                .append("mint", MSOL_MINT).append("symbol", "MSOL")
                                .append("tokenAmount", DAMM_MSOL_QTY.doubleValue()),
                        new Document("fromUserAccount", DAMM_POOL).append("toUserAccount", WALLET)
                                .append("mint", MLP_MINT).append("symbol", "MLP")
                                .append("tokenAmount", DAMM_MLP_QTY.doubleValue())));
        return raw("dammEntrySig", parsed);
    }

    private static RawTransaction dammExitRaw() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY")
                .append("fee", 5_000L)
                .append("timestamp", 1_775_692_800L)
                .append("instructions", List.of(dammLiquidityInstruction()))
                .append("tokenTransfers", List.of(
                        new Document("fromUserAccount", DAMM_POOL).append("toUserAccount", WALLET)
                                .append("mint", SolanaProgramIds.WSOL_MINT).append("symbol", "SOL")
                                .append("tokenAmount", DAMM_SOL_QTY.doubleValue()),
                        new Document("fromUserAccount", DAMM_POOL).append("toUserAccount", WALLET)
                                .append("mint", MSOL_MINT).append("symbol", "MSOL")
                                .append("tokenAmount", DAMM_MSOL_QTY.doubleValue()),
                        new Document("fromUserAccount", WALLET).append("toUserAccount", DAMM_POOL)
                                .append("mint", MLP_MINT).append("symbol", "MLP")
                                .append("tokenAmount", DAMM_MLP_QTY.doubleValue())))
                .append("accountData", List.of(
                        new Document("account", WALLET).append("nativeBalanceChange", 2_000_000L),
                        new Document("account", DAMM_USER_POOL_LP).append("nativeBalanceChange", -2_039_280L)));
        return raw("dammExitSig", parsed);
    }

    private static Document raydiumClmmLiquidityInstruction() {
        // increase/decrease liquidity: accounts[0]=nftOwner(wallet), accounts[1]=nftAccount.
        return new Document("programId", SolanaProgramIds.RAYDIUM_CLMM).append("accounts", List.of(
                WALLET, RAYDIUM_NFT_ACCOUNT, "poolState", "protocolPosition", "personalPosition",
                "tickLower", "tickUpper", "tokenAcc0", "tokenAcc1", "tokenVault0", "tokenVault1",
                SolanaProgramIds.TOKEN_PROGRAM));
    }

    private static RawTransaction raydiumEntryRaw() {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("timestamp", 1_772_323_200L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", WALLET)
                        .append("toUserAccount", RAYDIUM_POOL_VAULT)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", SOL_QTY.doubleValue())));
        return raw("raydiumEntrySig", parsed);
    }

    private static RawTransaction raydiumExitRaw() {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("timestamp", 1_773_100_800L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", RAYDIUM_POOL_VAULT)
                        .append("toUserAccount", WALLET)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", SOL_QTY.doubleValue())));
        return raw("raydiumExitSig", parsed);
    }

    /**
     * Raydium CLMM remove-liquidity returning {@code solReturned} SOL. When {@code fullClose} is true
     * the position NFT account carries a negative {@code nativeBalanceChange} (rent reclaimed), which
     * the resolver reads as terminal closure → LP_EXIT_FINAL.
     */
    private static RawTransaction raydiumExitRaw(String solReturned, boolean fullClose) {
        Document parsed = new Document("type", "SWAP")
                .append("fee", 5_000L)
                .append("timestamp", 1_773_100_800L)
                .append("instructions", List.of(raydiumClmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", RAYDIUM_POOL_VAULT)
                        .append("toUserAccount", WALLET)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", new BigDecimal(solReturned).doubleValue())))
                .append("accountData", List.of(new Document("account", RAYDIUM_NFT_ACCOUNT)
                        .append("nativeBalanceChange", fullClose ? -2_074_080L : 0L)));
        return raw("raydiumExit-" + solReturned + "-" + fullClose, parsed);
    }

    /**
     * Meteora DLMM remove-liquidity returning {@code solReturned} SOL. When {@code fullClose} is true
     * the position PDA carries a negative {@code nativeBalanceChange} (rent reclaimed) → LP_EXIT_FINAL.
     */
    private static RawTransaction dlmmExitRaw(String solReturned, boolean fullClose) {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE")
                .append("fee", 5_000L)
                .append("timestamp", 1_770_681_600L)
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", POOL)
                        .append("toUserAccount", WALLET)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", new BigDecimal(solReturned).doubleValue())))
                .append("accountData", List.of(new Document("account", POSITION)
                        .append("nativeBalanceChange", fullClose ? -57_406_080L : 0L)));
        return raw("dlmmExit-" + solReturned + "-" + fullClose, parsed);
    }

    private static Document dlmmLiquidityInstruction() {
        return new Document("programId", SolanaProgramIds.METEORA_DLMM).append("accounts", List.of(
                POSITION, POOL, SolanaProgramIds.METEORA_DLMM, "userTokenX", "userTokenY",
                "reserveX", "reserveY", SolanaProgramIds.WSOL_MINT, "binLower", "binUpper", WALLET));
    }

    private static RawTransaction raw(String signature, Document heliusParsed) {
        RawTransaction r = new RawTransaction();
        r.setId(signature + ":SOLANA:" + WALLET);
        r.setTxHash(signature);
        r.setWalletAddress(WALLET);
        r.setNetworkId("SOLANA");
        r.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return r;
    }

    private static RawTransaction entryRaw() {
        Document parsed = new Document("type", "ADD_LIQUIDITY_BY_STRATEGY")
                .append("fee", 5_000L)
                .append("timestamp", 1_769_904_000L)
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", WALLET)
                        .append("toUserAccount", POOL)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", SOL_QTY.doubleValue())));
        return raw("entrySig", parsed);
    }

    private static RawTransaction exitRaw() {
        Document parsed = new Document("type", "REMOVE_LIQUIDITY_BY_RANGE")
                .append("fee", 5_000L)
                .append("timestamp", 1_770_681_600L)
                .append("instructions", List.of(dlmmLiquidityInstruction()))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", POOL)
                        .append("toUserAccount", WALLET)
                        .append("mint", SolanaProgramIds.WSOL_MINT)
                        .append("symbol", "SOL")
                        .append("tokenAmount", SOL_QTY.doubleValue())));
        return raw("exitSig", parsed);
    }
}
