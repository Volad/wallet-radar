package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.ContinuityBucket;
import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.application.costbasis.application.replay.state.ContinuityStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.canonical.correlation.CorrelationContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B-ETH-02 — end-of-replay basis conservation telemetry for lending-loop continuity buckets.
 *
 * <p>{@link CorridorBasisConservationGuard} only watches the pending-transfer queues
 * ({@code corr-family:} / {@code bridge:} / …) and does NOT observe {@link ContinuityBucket}s, so a
 * linked {@code LENDING_LOOP_OPEN} that parks collateral basis and its later
 * {@code LENDING_LOOP_DECREASE}/{@code LENDING_LOOP_CLOSE} restores are invisible to it.
 *
 * <p>This guard asserts, per {@code lending-loop:} bucket and to the cent, the invariant
 * <pre>Σ parked (carry-out) == Σ restored (carry-in) + residual (still-open parked)</pre>
 * using the bucket's lifetime accumulators. The invariant is an internal soundness check on the
 * bucket park/restore mechanics: a violation means basis was mutated outside {@code add()/take()}
 * (e.g. a new code path draining the bucket). A residual balance is expected and legitimate for a
 * loop that is still open at end of replay, so it is reported as telemetry, not a breach.
 *
 * <p><b>Policy:</b> {@link Severity#WARN}. Unlike the corridor guard this never hard-fails — an
 * open loop leaving residual parked basis is a normal steady state, and the design mandates
 * telemetry (log/metric) rather than blocking the snapshot.
 */
@Component
@Slf4j
public class LendingLoopBasisConservationGuard {

    /** Cent-level tolerance for the parked == restored + residual identity. */
    static final BigDecimal CONSERVATION_EPSILON_USD = new BigDecimal("0.01");

    public enum Severity {
        WARN
    }

    public record LoopBucketResidual(
            String continuityIdentity,
            BigDecimal parkedBasisUsd,
            BigDecimal restoredBasisUsd,
            BigDecimal residualBasisUsd
    ) {
    }

    public record Result(
            List<LoopBucketResidual> buckets,
            List<LoopBucketResidual> breaches,
            BigDecimal totalParkedBasisUsd,
            BigDecimal totalRestoredBasisUsd,
            BigDecimal totalResidualBasisUsd
    ) {
        static Result empty() {
            return new Result(List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public Result evaluate(ReplayExecutionState replayState) {
        if (replayState == null) {
            return Result.empty();
        }
        return evaluate(replayState.continuity());
    }

    public Result evaluate(ContinuityStore continuityStore) {
        if (continuityStore == null) {
            return Result.empty();
        }
        List<LoopBucketResidual> buckets = new ArrayList<>();
        List<LoopBucketResidual> breaches = new ArrayList<>();
        BigDecimal totalParked = BigDecimal.ZERO;
        BigDecimal totalRestored = BigDecimal.ZERO;
        BigDecimal totalResidual = BigDecimal.ZERO;

        for (Map.Entry<ContinuityKey, ContinuityBucket> entry : continuityStore.asMap().entrySet()) {
            ContinuityKey key = entry.getKey();
            if (key == null || key.continuityIdentity() == null
                    || !key.continuityIdentity().startsWith(CorrelationContract.LENDING_LOOP_PREFIX)) {
                continue;
            }
            ContinuityBucket bucket = entry.getValue();
            BigDecimal parked = nz(bucket.cumulativeAddedCostBasisUsd());
            BigDecimal restored = nz(bucket.cumulativeTakenCostBasisUsd());
            BigDecimal residual = nz(bucket.totalCostBasisUsd());
            LoopBucketResidual row = new LoopBucketResidual(key.continuityIdentity(), parked, restored, residual);
            buckets.add(row);
            totalParked = totalParked.add(parked);
            totalRestored = totalRestored.add(restored);
            totalResidual = totalResidual.add(residual);

            BigDecimal imbalance = parked.subtract(restored).subtract(residual).abs();
            if (imbalance.compareTo(CONSERVATION_EPSILON_USD) > 0) {
                breaches.add(row);
            }
        }

        if (buckets.isEmpty()) {
            return Result.empty();
        }

        for (LoopBucketResidual breach : breaches) {
            log.warn(
                    "LENDING_LOOP_BASIS_CONSERVATION_BREACH severity={} bucket={} parkedUsd={} restoredUsd={} residualUsd={}",
                    Severity.WARN,
                    breach.continuityIdentity(),
                    breach.parkedBasisUsd(),
                    breach.restoredBasisUsd(),
                    breach.residualBasisUsd()
            );
        }
        log.info(
                "LENDING_LOOP_BASIS_CONSERVATION_SUMMARY buckets={} breaches={} parkedUsd={} restoredUsd={} residualUsd={}",
                buckets.size(),
                breaches.size(),
                totalParked,
                totalRestored,
                totalResidual
        );
        return new Result(buckets, breaches, totalParked, totalRestored, totalResidual);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
