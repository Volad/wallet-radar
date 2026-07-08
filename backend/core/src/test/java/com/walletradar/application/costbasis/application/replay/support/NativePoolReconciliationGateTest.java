package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.NativePoolReconciliationProperties;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * ADR-044 D4 — native-pool reconciliation gate.
 *
 * <p>The authoritative signal is terminal {@code |quantityAfter − onChainNative| > NATIVE_DUST}. The
 * lifetime {@code quantityShortfallAfter} / sticky {@code hasIncompleteHistoryAfter} counters are
 * informational only and must NOT drive a breach (audit:
 * {@code docs/tasks/audit-coverage-shortfall-avco-root-cause.md}).
 */
class NativePoolReconciliationGateTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final Map<String, BigDecimal> ON_CHAIN_BASE =
            Map.of("BASE|" + WALLET, new BigDecimal("0.000794"));

    private static NativePoolReconciliationGate gate(NativePoolReconciliationGate.Severity severity) {
        NativePoolReconciliationProperties properties = new NativePoolReconciliationProperties();
        properties.setSeverity(severity);
        return new NativePoolReconciliationGate(mock(MongoOperations.class), properties);
    }

    private static AssetLedgerPoint nativeBasePoint(
            BigDecimal quantityAfter,
            BigDecimal shortfallAfter,
            boolean incompleteHistory
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setAccountingUniverseId("u1");
        point.setWalletAddress(WALLET);
        point.setNetworkId(NetworkId.BASE);
        point.setAccountingAssetIdentity("NATIVE:BASE");
        point.setAssetSymbol("ETH");
        point.setTxHash("0xterminal");
        point.setReplaySequence(100L);
        point.setQuantityAfter(quantityAfter);
        point.setQuantityShortfallAfter(shortfallAfter);
        point.setHasIncompleteHistoryAfter(incompleteHistory);
        return point;
    }

    @Test
    @DisplayName("terminal quantity matching on-chain within dust passes even with a nonzero lifetime shortfall counter")
    void matchingOnChainPassesDespiteNonzeroShortfallCounter() {
        // Terminal quantity reconciles to on-chain (0.000794) within dust, yet the misleading
        // lifetime shortfall counter is large and incomplete-history is sticky-true. This must pass.
        AssetLedgerPoint terminal = nativeBasePoint(
                new BigDecimal("0.000794"), new BigDecimal("0.126290492105982958"), true);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(terminal), ON_CHAIN_BASE);

        assertThat(result.conserved()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }

    @Test
    @DisplayName("terminal quantity diverging from on-chain beyond dust breaches (ON_CHAIN_BALANCE_MISMATCH only)")
    void divergentOnChainQuantityBreaches() {
        AssetLedgerPoint terminal = nativeBasePoint(
                new BigDecimal("0.0000179"), new BigDecimal("0.126290492105982958"), true);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(terminal), ON_CHAIN_BASE);

        assertThat(result.conserved()).isFalse();
        assertThat(result.breaches())
                .extracting(NativePoolReconciliationResult.Breach::kind)
                .containsExactly(NativePoolReconciliationResult.Kind.ON_CHAIN_BALANCE_MISMATCH);
        // Lifetime counters are surfaced as informational fields on the breach, not as separate breaches.
        NativePoolReconciliationResult.Breach breach = result.breaches().getFirst();
        assertThat(breach.shortfallQuantity()).isEqualByComparingTo("0.126290492105982958");
        assertThat(breach.hasIncompleteHistory()).isTrue();
        assertThat(breach.onChainQuantity()).isEqualByComparingTo("0.000794");
    }

    @Test
    @DisplayName("pool with no on-chain ground truth is carved out (never breaches on null)")
    void missingOnChainGroundTruthCarvedOut() {
        // Large lifetime shortfall + sticky incomplete-history, but no on-chain balance for the pool.
        AssetLedgerPoint terminal = nativeBasePoint(
                new BigDecimal("0.0000179"), new BigDecimal("0.126290492105982958"), true);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(terminal), Map.of());

        assertThat(result.conserved()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }

    @Test
    @DisplayName("HARD_FAIL severity throws on an on-chain-divergent pool")
    void hardFailThrows() {
        AssetLedgerPoint terminal = nativeBasePoint(
                new BigDecimal("0.0000179"), new BigDecimal("0.126290492105982958"), true);

        assertThatThrownBy(() ->
                gate(NativePoolReconciliationGate.Severity.HARD_FAIL).evaluate(List.of(terminal), ON_CHAIN_BASE))
                .isInstanceOf(NativePoolReconciliationException.class);
    }

    @Test
    @DisplayName("non-EVM native (SOL) pool is carved out")
    void nonEvmNativeCarvedOut() {
        AssetLedgerPoint solPoint = new AssetLedgerPoint();
        solPoint.setWalletAddress(WALLET);
        solPoint.setNetworkId(NetworkId.SOLANA);
        solPoint.setAccountingAssetIdentity("NATIVE:SOLANA");
        solPoint.setAssetSymbol("SOL");
        solPoint.setReplaySequence(5L);
        solPoint.setQuantityAfter(new BigDecimal("3"));
        solPoint.setQuantityShortfallAfter(new BigDecimal("2"));
        solPoint.setHasIncompleteHistoryAfter(true);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(solPoint), Map.of());

        assertThat(result.conserved()).isTrue();
    }

    @Test
    @DisplayName("$0 native carry-out over a covered pool is flagged (Invariant b)")
    void zeroBasisCarryOutOverCoveredPoolFlagged() {
        AssetLedgerPoint carryOut = new AssetLedgerPoint();
        carryOut.setWalletAddress(WALLET);
        carryOut.setNetworkId(NetworkId.BASE);
        carryOut.setAccountingAssetIdentity("NATIVE:BASE");
        carryOut.setAssetSymbol("ETH");
        carryOut.setTxHash("0xbridgeout");
        carryOut.setReplaySequence(50L);
        carryOut.setNormalizedType("BRIDGE_OUT");
        carryOut.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        carryOut.setQuantityDelta(new BigDecimal("-0.1"));
        carryOut.setCostBasisDeltaUsd(BigDecimal.ZERO);
        carryOut.setQuantityBefore(new BigDecimal("0.5"));
        carryOut.setTotalCostBasisBeforeUsd(new BigDecimal("1500"));
        // terminal state is otherwise clean so the only breach is the ghost carry-out.
        carryOut.setQuantityAfter(new BigDecimal("0.4"));
        carryOut.setQuantityShortfallAfter(BigDecimal.ZERO);
        carryOut.setHasIncompleteHistoryAfter(false);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(carryOut), Map.of());

        assertThat(result.conserved()).isFalse();
        assertThat(result.breaches())
                .extracting(NativePoolReconciliationResult.Breach::kind)
                .contains(NativePoolReconciliationResult.Kind.ZERO_BASIS_CARRY_OUT);
    }

    @Test
    @DisplayName("dust-only native gas carry-out is not flagged (Invariant b dust gate)")
    void dustOnlyCarryOutNotFlagged() {
        AssetLedgerPoint dustCarryOut = new AssetLedgerPoint();
        dustCarryOut.setWalletAddress(WALLET);
        dustCarryOut.setNetworkId(NetworkId.BASE);
        dustCarryOut.setAccountingAssetIdentity("NATIVE:BASE");
        dustCarryOut.setAssetSymbol("ETH");
        dustCarryOut.setTxHash("0xdustgas");
        dustCarryOut.setReplaySequence(60L);
        dustCarryOut.setNormalizedType("BRIDGE_OUT");
        dustCarryOut.setBasisEffect(AssetLedgerPoint.BasisEffect.CARRY_OUT);
        // Dust-only gas leg (~1e-7) over a covered pool: must NOT breach.
        dustCarryOut.setQuantityDelta(new BigDecimal("-0.0000001"));
        dustCarryOut.setCostBasisDeltaUsd(BigDecimal.ZERO);
        dustCarryOut.setQuantityBefore(new BigDecimal("0.5"));
        dustCarryOut.setTotalCostBasisBeforeUsd(new BigDecimal("1500"));
        dustCarryOut.setQuantityAfter(new BigDecimal("0.4999999"));
        dustCarryOut.setQuantityShortfallAfter(BigDecimal.ZERO);
        dustCarryOut.setHasIncompleteHistoryAfter(false);

        NativePoolReconciliationResult result =
                gate(NativePoolReconciliationGate.Severity.WARN).evaluate(List.of(dustCarryOut), Map.of());

        assertThat(result.conserved()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }
}
