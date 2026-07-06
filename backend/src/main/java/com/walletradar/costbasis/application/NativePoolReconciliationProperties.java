package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.support.NativePoolReconciliationGate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-044 D4 — runtime settings for the native-pool reconciliation gate. Modeled on the
 * {@code CorridorBasisConservationGuard} WARN→HARD_FAIL staged rollout, but exposed as config so the
 * one-line flip is a YAML change rather than a recompile.
 */
@ConfigurationProperties(prefix = "walletradar.costbasis.native-pool-reconciliation")
@NoArgsConstructor
@Getter
@Setter
public class NativePoolReconciliationProperties {

    /**
     * Gate severity. Defaults {@link NativePoolReconciliationGate.Severity#WARN} (structured
     * diagnostic, does not fail replay). Flip to {@code HARD_FAIL} only after a clean rebuild shows
     * every in-scope {@code NATIVE:<chain>} pool reconciled within dust.
     */
    private NativePoolReconciliationGate.Severity severity = NativePoolReconciliationGate.Severity.WARN;
}
