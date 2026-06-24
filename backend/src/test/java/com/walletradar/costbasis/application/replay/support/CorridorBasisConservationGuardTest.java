package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.BridgePendingKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.TransferPendingKey;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-9 WS-3 / D3 — end-of-replay corridor/bridge basis conservation guard.
 * Covers T-2 (no orphan when matched), T-11 (fires on orphaned carry-out),
 * T-12 (no false positive on a legit orphan IN / non-guarded queue), T-13 (net conserved → silent).
 */
class CorridorBasisConservationGuardTest {

    private static final AssetKey ETH =
            new AssetKey("0xwallet", NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");

    private final CorridorBasisConservationGuard guard = new CorridorBasisConservationGuard();

    private ReplayExecutionState newState() {
        return new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
    }

    private static CarryTransfer coveredCarryOut(String qty, String basisUsd) {
        BigDecimal q = new BigDecimal(qty);
        BigDecimal basis = new BigDecimal(basisUsd);
        // avco is irrelevant to the guard; pass null to avoid non-terminating division noise.
        return new CarryTransfer(q, q, BigDecimal.ZERO, basis, null, false, ETH);
    }

    @Test
    @DisplayName("T-2: a matched corridor pair leaves no residual carry-out → conserved")
    void matchedPairIsConserved() {
        ReplayExecutionState state = newState();
        // matched legs were already drained at replay time; nothing left in the queue.
        state.pendingTransfers().queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xabc:FAMILY:ETH"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }

    @Test
    @DisplayName("T-11: an orphaned covered carry-out in a corr-family queue fires a breach")
    void orphanedCorridorCarryOutFiresBreach() {
        ReplayExecutionState state = newState();
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xabc:FAMILY:ETH"))
                .addLast(coveredCarryOut("3.06", "9272.11"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isFalse();
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().getFirst().assetSymbol()).isEqualTo("ETH");
        assertThat(result.totalOrphanedBasisUsd()).isEqualByComparingTo("9272.11");
    }

    @Test
    @DisplayName("T-11: an orphaned covered carry-out in a bridge queue fires a breach (RC-7)")
    void orphanedBridgeCarryOutFiresBreach() {
        ReplayExecutionState state = newState();
        state.pendingTransfers()
                .queue(new BridgePendingKey("bridge:lifi:0xdeadbeef:FAMILY:ETH"))
                .addLast(coveredCarryOut("0.0116", "30.00"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isFalse();
        assertThat(result.breaches()).hasSize(1);
    }

    @Test
    @DisplayName("T-12: a legit pending-inbound (orphan IN) is not a breach; nor is a non-guarded queue")
    void pendingInboundAndNonGuardedQueueAreNotBreaches() {
        ReplayExecutionState state = newState();
        // Genuine orphan IN (CEX→wallet withdrawal credit, no on-chain carry-out): a pendingInbound.
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xabc:FAMILY:ETH"))
                .addLast(CarryTransfer.pendingInbound(new BigDecimal("3.06"), ETH, BigDecimal.ZERO));
        // A covered carry-out parked on a NON-guarded queue (e.g. plain tx:) must not be swept.
        state.pendingTransfers()
                .queue(new TransferPendingKey("tx:0xabc:FAMILY:ETH:3.06"))
                .addLast(coveredCarryOut("3.06", "9272.11"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isTrue();
    }

    @Test
    @DisplayName("T-8: batched carry-outs sum (Σ-conservation) — orphaned slices aggregate to the released total")
    void batchedCarryOutsAggregateToReleasedTotal() {
        ReplayExecutionState state = newState();
        TransferPendingKey key = new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xabc:FAMILY:ETH");
        // Two slices of one released CARRY_OUT (Σ qty = 3.06, Σ basis = 9272.11) left unmatched.
        state.pendingTransfers().queue(key).addLast(coveredCarryOut("1.50", "4500.00"));
        state.pendingTransfers().queue(key).addLast(coveredCarryOut("1.56", "4772.11"));

        CorridorBasisConservationResult both = guard.evaluate(state);
        assertThat(both.breaches()).hasSize(2);
        assertThat(both.totalOrphanedBasisUsd()).isEqualByComparingTo("9272.11");

        // One slice inherited (drained); the remaining residual equals exactly the other slice.
        state.pendingTransfers().find(key).removeFirst();
        CorridorBasisConservationResult remaining = guard.evaluate(state);
        assertThat(remaining.breaches()).hasSize(1);
        assertThat(remaining.totalOrphanedBasisUsd()).isEqualByComparingTo("4772.11");
    }

    @Test
    @DisplayName("G-1: a cross-asset corridor swap (USDE→USDT) is not flagged — counterpart leg differs")
    void crossAssetCorridorSwapIsSuppressed() {
        ReplayExecutionState state = newState();
        AssetKey usde = new AssetKey("BYBIT:1", null, "SYMBOL:USDE", "USDE", "SYMBOL:USDE");
        AssetKey usdt = new AssetKey("0xwallet", NetworkId.ARBITRUM, "SYMBOL:USDT", "USDT", "SYMBOL:USDT");
        // Source-leg covered carry-out (USDE) released into a DIFFERENT-asset destination.
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:ARBITRUM:0xswap:SYMBOL:USDE"))
                .addLast(new CarryTransfer(
                        new BigDecimal("862"), new BigDecimal("862"), BigDecimal.ZERO,
                        new BigDecimal("862"), null, false, usde));
        // Destination credit (USDT) on the SAME corridor base, different asset → legitimate swap.
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:ARBITRUM:0xswap:SYMBOL:USDT"))
                .addLast(CarryTransfer.pendingInbound(new BigDecimal("862"), usdt, BigDecimal.ZERO));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isTrue();
    }

    @Test
    @DisplayName("G-1: an out-of-scope family carry (SOL) is expected, not a breach")
    void outOfScopeFamilyCarryIsSuppressed() {
        ReplayExecutionState state = newState();
        AssetKey sol = new AssetKey("BYBIT:1", null, "SYMBOL:SOL", "SOL", "FAMILY:SOL");
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:SOLANA:0xoos:FAMILY:SOL"))
                .addLast(new CarryTransfer(
                        new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO,
                        new BigDecimal("277"), null, false, sol));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isTrue();
    }

    @Test
    @DisplayName("G-1 negative: a genuine same-asset orphan (ETH→ETH credit took spot) still WARNs")
    void genuineSameAssetOrphanStillFiresAfterScopeChange() {
        ReplayExecutionState state = newState();
        // Same-asset corridor: the credit took spot/$0 (no sibling different-asset leg), so the
        // released ETH carry-out is a genuine orphan and must still be flagged.
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xeth:FAMILY:ETH"))
                .addLast(coveredCarryOut("0.148", "391.85"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isFalse();
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().getFirst().assetSymbol()).isEqualTo("ETH");
        assertThat(result.totalOrphanedBasisUsd()).isEqualByComparingTo("391.85");
    }

    @Test
    @DisplayName("T-13: dust below the residual epsilon is not reported (net conserved)")
    void dustBelowEpsilonIsConserved() {
        ReplayExecutionState state = newState();
        state.pendingTransfers()
                .queue(new TransferPendingKey("corr-family:BYBIT-CORRIDOR:MANTLE:0xabc:FAMILY:ETH"))
                .addLast(coveredCarryOut("0.0001", "0.30"));

        CorridorBasisConservationResult result = guard.evaluate(state);

        assertThat(result.conserved()).isTrue();
    }
}
