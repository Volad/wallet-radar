package com.walletradar.costbasis.application.replay.support;

/**
 * ADR-044 D4 — thrown only when the native-pool reconciliation gate severity is promoted to
 * {@code HARD_FAIL} and an in-scope {@code NATIVE:<chain>} pool fails to reconcile within dust at
 * end of replay.
 */
public class NativePoolReconciliationException extends RuntimeException {

    public NativePoolReconciliationException(int breachCount) {
        super("Native-pool reconciliation breach: " + breachCount
                + " NATIVE:<chain> pool(s) failed to reconcile within dust");
    }
}
