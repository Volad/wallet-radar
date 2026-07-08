package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.support.OutOfScopeFamilySupport;
import com.walletradar.application.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.application.costbasis.application.replay.state.PendingTransferStore;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RC-9 / RC-7 D3 — end-of-replay corridor/bridge basis conservation guard.
 *
 * <p>After the last event is dispatched, every released {@code CARRY_OUT} on a continuity corridor
 * or bridge must have been inherited by a matched credit. Any covered basis still parked in a
 * {@code corr-family:} / {@code bridge:} / {@code bridge-settlement:} queue above the residual
 * epsilon is an <b>orphan</b>: the matched credit took a residual/spot/$0 basis instead of the
 * released carry, violating the RC-9 invariant.
 *
 * <p><b>Policy:</b> {@link #SEVERITY} is permanently {@link Severity#HARD_FAIL} — a breach means a
 * real accounting scenario is not covered and must block the replay/snapshot rather than degrade
 * silently. There is no runtime toggle (system property, env var, or otherwise) to soften this to
 * {@link Severity#WARN}; any such escape hatch defeats the invariant this guard exists to enforce.
 * If a breach needs to be tolerated temporarily, fix the underlying data/logic issue or explicitly
 * carve out the specific case in {@link #isOutOfScopeCarry} / {@link #isCrossAssetCorridorSwap},
 * not by disabling the guard.
 */
@Component
@Slf4j
public class CorridorBasisConservationGuard {

    /** RC-9 D3 — always HARD_FAIL. Do not reintroduce a WARN fallback or a property-based override. */
    static final Severity SEVERITY = Severity.HARD_FAIL;

    /**
     * RC-9 open question Q3 — residual tolerance band. Carries below this USD basis are treated as
     * dust (fee/rounding) and not reported as orphans. Reuses the conservative ~$1 carry epsilon.
     */
    static final BigDecimal RESIDUAL_BASIS_EPSILON_USD = new BigDecimal("1.00");

    /**
     * RC-A (ADR-043) — Bybit earn-principal / venue-internal queues are guarded alongside the
     * corridor/bridge queues. Paired earn-principal legs ({@code bybit-earn-principal-v1:} /
     * {@code bybit-earn-onchain-fund-v1:}) and every collapsed FUND↔UTA↔EARN pair route to
     * {@code corr-family:}; the same-UID venue FIFO (Earn subscribe/redeem, universal transfers)
     * routes to {@code bybit-earn-carry:}. A leftover {@code CARRY_OUT} on either queue with no
     * matched {@code CARRY_IN} at end of replay is a genuinely-unpaired boundary leg (RC-B), which
     * must surface as {@code CORRIDOR_BASIS_IMBALANCE} rather than silently drop inventory. The $1
     * dust epsilon and the out-of-scope carve-out keep open-position and OOS-venue residues quiet.
     */
    private static final List<String> GUARDED_QUEUE_PREFIXES =
            List.of("corr-family:", "bridge:", "bridge-settlement:", "bybit-earn-carry:");

    /**
     * G-1 (WS-E) — queue keys end with the asset/family identity (e.g. {@code :SYMBOL:USDE} or
     * {@code :FAMILY:ETH}). The portion before the last such marker is the corridor base
     * (prefix + correlation id). Two legs of the same corridor that carry different assets share the
     * corridor base but differ in the asset suffix — that is a legitimate cross-asset swap, not an
     * orphaned carry.
     */
    private static final String SYMBOL_MARKER = ":SYMBOL:";
    private static final String FAMILY_MARKER = ":FAMILY:";

    public enum Severity {
        WARN,
        HARD_FAIL
    }

    /**
     * Sweeps the pending-transfer store for orphaned released covered carries on guarded queues.
     * Logs a structured breach per orphan; under {@link Severity#HARD_FAIL} throws after logging.
     *
     * @return the conservation result (empty {@code breaches} means conserved).
     */
    public CorridorBasisConservationResult evaluate(ReplayExecutionState replayState) {
        if (replayState == null) {
            return CorridorBasisConservationResult.empty();
        }
        PendingTransferStore pendingTransfers = replayState.pendingTransfers();
        List<PendingTransferStore.ResidualCoveredCarry> residuals =
                pendingTransfers.residualCoveredCarries(RESIDUAL_BASIS_EPSILON_USD);

        // G-1 (WS-E): corridor-level scope. Map each corridor base to the distinct asset suffixes
        // observed across ALL non-empty pending queues, so a leftover CARRY_OUT can consult its
        // corridor's counterpart leg (not just its own symbol).
        Map<String, Set<String>> assetSuffixesByCorridor = assetSuffixesByCorridor(pendingTransfers);

        List<CorridorBasisConservationResult.Breach> breaches = residuals.stream()
                .filter(residual -> isGuardedQueue(residual.queueKey()))
                .filter(residual -> !isCrossAssetCorridorSwap(residual.queueKey(), assetSuffixesByCorridor))
                .filter(residual -> !isOutOfScopeCarry(residual.carry()))
                .map(residual -> new CorridorBasisConservationResult.Breach(
                        residual.queueKey(),
                        residual.carry().assetKey() == null ? null : residual.carry().assetKey().assetSymbol(),
                        residual.carry().quantity(),
                        residual.carry().costBasisUsd()
                ))
                .toList();

        if (breaches.isEmpty()) {
            return CorridorBasisConservationResult.empty();
        }

        BigDecimal orphanedBasisUsd = breaches.stream()
                .map(CorridorBasisConservationResult.Breach::orphanedBasisUsd)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (CorridorBasisConservationResult.Breach breach : breaches) {
            log.warn(
                    "CORRIDOR_BASIS_CONSERVATION_BREACH severity={} queueKey={} asset={} orphanedQty={} orphanedBasisUsd={}",
                    SEVERITY,
                    breach.queueKey(),
                    breach.assetSymbol(),
                    breach.orphanedQuantity(),
                    breach.orphanedBasisUsd()
            );
        }
        log.warn(
                "CORRIDOR_BASIS_CONSERVATION_SUMMARY severity={} breaches={} totalOrphanedBasisUsd={}",
                SEVERITY,
                breaches.size(),
                orphanedBasisUsd
        );

        if (SEVERITY == Severity.HARD_FAIL) {
            throw new CorridorBasisConservationException(new CorridorBasisConservationResult(breaches, orphanedBasisUsd));
        }
        return new CorridorBasisConservationResult(breaches, orphanedBasisUsd);
    }

    /**
     * G-1 (WS-E): a leftover CARRY_OUT is a legitimate cross-asset swap (not an orphan) when its
     * corridor's matched counterpart leg carries a DIFFERENT asset/family. Detected by consulting
     * the corridor base: if another non-empty queue shares this corridor base but a different asset
     * suffix (e.g. USDE→USDT, CAKE swap), the source-leg carry was released into a different-asset
     * destination and must not be flagged. A same-asset destination whose credit took spot/$0 keeps
     * exactly one suffix for the corridor base and is still flagged.
     */
    private static boolean isCrossAssetCorridorSwap(
            String queueKey,
            Map<String, Set<String>> assetSuffixesByCorridor
    ) {
        String base = corridorBase(queueKey);
        String suffix = assetSuffix(queueKey);
        Set<String> suffixes = assetSuffixesByCorridor.get(base);
        if (suffixes == null) {
            return false;
        }
        for (String observed : suffixes) {
            if (!observed.equals(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * G-1 (WS-E): out-of-scope families (TON/SOL/HYPEREVM and peg-neutral OOS-venue carries) end at
     * the CEX boundary with no supported on-chain home, so a residual carry there is expected, not a
     * conservation breach. Uses the single OOS source of truth.
     */
    private static boolean isOutOfScopeCarry(CarryTransfer carry) {
        if (carry == null || carry.assetKey() == null) {
            return false;
        }
        return OutOfScopeFamilySupport.isOutOfScopeFamily(
                carry.assetKey().assetIdentity(),
                carry.assetKey().assetSymbol()
        );
    }

    private static Map<String, Set<String>> assetSuffixesByCorridor(PendingTransferStore pendingTransfers) {
        Map<String, Set<String>> byCorridor = new HashMap<>();
        for (String queueKey : pendingTransfers.nonEmptyQueueKeys()) {
            if (!isGuardedQueue(queueKey)) {
                continue;
            }
            byCorridor
                    .computeIfAbsent(corridorBase(queueKey), ignored -> new HashSet<>())
                    .add(assetSuffix(queueKey));
        }
        return byCorridor;
    }

    private static String corridorBase(String queueKey) {
        int cut = lastAssetMarker(queueKey);
        return cut < 0 ? queueKey : queueKey.substring(0, cut);
    }

    private static String assetSuffix(String queueKey) {
        int cut = lastAssetMarker(queueKey);
        return cut < 0 ? "" : queueKey.substring(cut);
    }

    private static int lastAssetMarker(String queueKey) {
        if (queueKey == null) {
            return -1;
        }
        return Math.max(queueKey.lastIndexOf(SYMBOL_MARKER), queueKey.lastIndexOf(FAMILY_MARKER));
    }

    private static boolean isGuardedQueue(String queueKey) {
        if (queueKey == null) {
            return false;
        }
        for (String prefix : GUARDED_QUEUE_PREFIXES) {
            if (queueKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
