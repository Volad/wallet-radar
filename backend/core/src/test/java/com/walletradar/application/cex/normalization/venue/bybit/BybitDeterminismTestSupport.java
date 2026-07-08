package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test helper for the RC-9 D1 determinism fixes across the Bybit normalization package
 * (see {@code docs/adr/ADR-029-deterministic-cex-corridor-basis-continuity.md} §D1 and
 * {@code docs/tasks/bybit-collapser-determinism-fix-implementation-plan.md}).
 *
 * <p>Every fixed method in this package selects one canonical document among 2+ tied/near-tied
 * candidates. Its correctness claim is that the selection is a <b>pure function of the candidate
 * set</b>, not of the (arbitrary) Mongo scan / list order the candidates happen to arrive in. This
 * helper extracts the "run twice with a reordered-but-object-identical candidate list, then assert
 * every mutable field of interest is unchanged" boilerplate that would otherwise be duplicated once
 * per fixed method.</p>
 *
 * <p><b>What this helper does NOT do:</b> it does not stub {@code mongoOperations.find(...)}. Each
 * call site remains responsible for wiring its own stub sequence in strict call order (a single
 * {@code .thenReturn(orderA).thenReturn(orderB)} two-value chain for methods with exactly one
 * {@code find()} call per invocation, or the four-value
 * {@code [run1Main, run1Symmetry, run2Main, run2Symmetry]} sequence for
 * {@link BybitStreamAuthorityCollapser#collapseMirrors()}, which makes two separate {@code find()}
 * calls per invocation) — that stubbing is method-specific and must stay explicit in each test.</p>
 */
final class BybitDeterminismTestSupport {

    private BybitDeterminismTestSupport() {
    }

    /**
     * Field-level snapshot of the mutable state a determinism fix must keep stable across a
     * reordered-scan repeat invocation: {@code correlationId}, {@code excludedFromAccounting},
     * {@code accountingExclusionReason}, {@code continuityCandidate}.
     */
    private record Snapshot(
            String correlationId,
            Boolean excludedFromAccounting,
            String accountingExclusionReason,
            Boolean continuityCandidate
    ) {
    }

    private static Map<String, Snapshot> snapshotAll(List<NormalizedTransaction> docs) {
        Map<String, Snapshot> snapshot = new LinkedHashMap<>();
        for (NormalizedTransaction tx : docs) {
            snapshot.put(tx.getId(), new Snapshot(
                    tx.getCorrelationId(),
                    tx.getExcludedFromAccounting(),
                    tx.getAccountingExclusionReason(),
                    tx.getContinuityCandidate()
            ));
        }
        return snapshot;
    }

    /**
     * Runs {@code firstRun}, snapshots {@code docs}' mutable fields, runs {@code secondRun}
     * (against a reordered/reversed-but-object-identical candidate list already wired into the
     * caller's mocks), and asserts the post-second-run snapshot is field-for-field identical to
     * the post-first-run snapshot.
     *
     * @param docs      every document whose {@code correlationId}/{@code excludedFromAccounting}/
     *                  {@code accountingExclusionReason}/{@code continuityCandidate} state must be
     *                  proven stable across the two runs (the SAME object instances the mocked
     *                  {@code find()} calls return in both runs, just reordered)
     * @param firstRun  invokes the method under test for run 1
     * @param secondRun invokes the method under test for run 2
     */
    static void assertReorderInvariant(
            List<NormalizedTransaction> docs,
            Runnable firstRun,
            Runnable secondRun
    ) {
        firstRun.run();
        Map<String, Snapshot> afterRun1 = snapshotAll(docs);

        secondRun.run();
        Map<String, Snapshot> afterRun2 = snapshotAll(docs);

        assertThat(afterRun2)
                .as("correlationId/excludedFromAccounting/accountingExclusionReason/continuityCandidate "
                        + "must be identical after a reordered-scan repeat invocation")
                .isEqualTo(afterRun1);
    }
}
