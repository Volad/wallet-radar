package com.walletradar.application.liquiditypools.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4 — Solana LP open/closed detection. Solana DLMM/CLMM concentrated-liquidity positions rebalance
 * their token amounts while in the pool, so the LP-receipt basis {@code qtyHeld} is not a reliable
 * open/closed signal (a fully withdrawn position keeps a non-zero residual). Closure is therefore
 * decided by the latest lifecycle event: a correlation whose most recent LP event is a terminal
 * {@code LP_EXIT} is closed, regardless of any residual basis pool. EVM partial exits
 * ({@code LP_EXIT_PARTIAL}) must stay open until {@code LP_EXIT_FINAL}, so EVM behavior is unchanged.
 */
class LpPositionClosedDetectionTest {

    private static final String SOLANA_CORR = "lp-position:solana:meteora-dlmm:HL53fsnZz8d5pzXM216i2bnXZXNiWPvBbd72BjoN1YHU";
    private static final String EVM_CORR = "lp-position:base:0xnfpm:12345";
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    // WS-8: concentrated-liquidity detection is driven by the stamped lpConcentrated capability, not
    // the correlation-id prefix. Solana DLMM/CLMM rows are stamped true; EVM CL-NFT rows are not.
    private static NormalizedTransaction tx(
            String correlationId, NormalizedTransactionType type, int order, boolean concentrated) {
        return new NormalizedTransaction()
                .setCorrelationId(correlationId)
                .setType(type)
                .setBlockTimestamp(T0.plusSeconds(order))
                .setLpConcentrated(concentrated);
    }

    private static NormalizedTransaction solanaTx(NormalizedTransactionType type, int order) {
        return tx(SOLANA_CORR, type, order, true);
    }

    private static NormalizedTransaction evmTx(NormalizedTransactionType type, int order) {
        return tx(EVM_CORR, type, order, false);
    }

    @Test
    void solanaLpExitWithoutBasisPoolClosesPosition() {
        List<NormalizedTransaction> txs = List.of(
                solanaTx(NormalizedTransactionType.LP_ENTRY, 1),
                solanaTx(NormalizedTransactionType.LP_EXIT, 2));

        Map<String, Boolean> closed = LpPositionRefreshService.computeClosedByCorrelation(txs, Set.of());

        assertThat(closed.get(SOLANA_CORR)).isTrue();
    }

    @Test
    void solanaLpExitClosesEvenWithResidualBasisPool() {
        List<NormalizedTransaction> txs = List.of(
                solanaTx(NormalizedTransactionType.LP_ENTRY, 1),
                solanaTx(NormalizedTransactionType.LP_EXIT, 2));

        Map<String, Boolean> closed =
                LpPositionRefreshService.computeClosedByCorrelation(txs, Set.of(SOLANA_CORR));

        // CL positions leave a non-zero residual basis after a full withdrawal, so the latest LP_EXIT
        // closes the position regardless of whether a basis pool still exists.
        assertThat(closed.get(SOLANA_CORR)).isTrue();
    }

    @Test
    void solanaReEntryAfterExitReopensPosition() {
        List<NormalizedTransaction> txs = List.of(
                solanaTx(NormalizedTransactionType.LP_ENTRY, 1),
                solanaTx(NormalizedTransactionType.LP_EXIT, 2),
                solanaTx(NormalizedTransactionType.LP_ENTRY, 3));

        Map<String, Boolean> closed = LpPositionRefreshService.computeClosedByCorrelation(txs, Set.of());

        // Latest event is a fresh entry after the exit → position is open again.
        assertThat(closed.get(SOLANA_CORR)).isFalse();
    }

    @Test
    void solanaOpenPositionWithoutExitStaysOpen() {
        List<NormalizedTransaction> txs = List.of(solanaTx(NormalizedTransactionType.LP_ENTRY, 1));

        Map<String, Boolean> closed = LpPositionRefreshService.computeClosedByCorrelation(txs, Set.of());

        assertThat(closed.get(SOLANA_CORR)).isFalse();
    }

    @Test
    void evmPartialExitStaysOpenUntilFinal() {
        List<NormalizedTransaction> partial = List.of(
                evmTx(NormalizedTransactionType.LP_ENTRY, 1),
                evmTx(NormalizedTransactionType.LP_EXIT_PARTIAL, 2));
        assertThat(LpPositionRefreshService.computeClosedByCorrelation(partial, Set.of()).get(EVM_CORR))
                .isFalse();

        List<NormalizedTransaction> finalExit = List.of(
                evmTx(NormalizedTransactionType.LP_ENTRY, 1),
                evmTx(NormalizedTransactionType.LP_EXIT_FINAL, 2));
        assertThat(LpPositionRefreshService.computeClosedByCorrelation(finalExit, Set.of()).get(EVM_CORR))
                .isTrue();
    }

    @Test
    void evmPlainLpExitDoesNotCloseEvmPosition() {
        // A bare LP_EXIT on an EVM (non-concentrated) correlation must NOT close — only stamped
        // concentrated-liquidity rows treat LP_EXIT as terminal.
        List<NormalizedTransaction> txs = List.of(
                evmTx(NormalizedTransactionType.LP_ENTRY, 1),
                evmTx(NormalizedTransactionType.LP_EXIT, 2));

        Map<String, Boolean> closed = LpPositionRefreshService.computeClosedByCorrelation(txs, Set.of());

        assertThat(closed.get(EVM_CORR)).isFalse();
    }
}
