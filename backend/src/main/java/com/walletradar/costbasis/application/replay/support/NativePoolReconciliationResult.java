package com.walletradar.costbasis.application.replay.support;

import com.walletradar.domain.common.NetworkId;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADR-044 D4 — structured outcome of the end-of-replay native-pool reconciliation sweep.
 * {@code conserved()} is true when every in-scope {@code NATIVE:<chain>} pool reconciled within dust.
 *
 * <p>Audit {@code docs/tasks/audit-coverage-shortfall-avco-root-cause.md} proved that
 * {@code quantityShortfallAfter} / {@code hasIncompleteHistoryAfter} are monotonic, lifetime-cumulative
 * counters that never feed final holdings (holdings anchor to {@code on_chain_balances} / venue
 * balances). They are therefore carried here as <b>informational</b> diagnostic fields only and never
 * drive a breach. The single authoritative correctness signal is the terminal per-pool reconciliation
 * of {@code quantityAfter} against the authoritative on-chain native balance.
 */
public record NativePoolReconciliationResult(List<Breach> breaches) {

    public static NativePoolReconciliationResult empty() {
        return new NativePoolReconciliationResult(List.of());
    }

    public boolean conserved() {
        return breaches == null || breaches.isEmpty();
    }

    /** The nature of a single native-pool reconciliation breach. */
    public enum Kind {
        /** Tracked terminal quantity diverges from the authoritative on-chain native balance (Invariant a). */
        ON_CHAIN_BALANCE_MISMATCH,
        /** A native carry-out emitted $0 basis while the source pool held covered basis (Invariant b). */
        ZERO_BASIS_CARRY_OUT
    }

    /**
     * One native-pool reconciliation breach.
     *
     * <p>{@code shortfallQuantity} and {@code hasIncompleteHistory} are <b>informational only</b>
     * (misleading monotonic lifetime counters per the audit); they are surfaced for diagnosis but do
     * not, on their own, constitute a breach.
     */
    public record Breach(
            NetworkId networkId,
            String walletAddress,
            String accountingAssetIdentity,
            Kind kind,
            BigDecimal trackedQuantity,
            BigDecimal onChainQuantity,
            BigDecimal shortfallQuantity,
            boolean hasIncompleteHistory,
            String firstOffendingTxHash
    ) {
    }
}
