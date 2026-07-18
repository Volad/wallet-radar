# BLOCKER-5 — Residual Bybit flagged points: shortfall-value reconciliation + same-second funding order

**Status:** DRAFT (Phase 2), revised after all 3 reviews. Stop gate before Phase 4 pending user approval.
**Slug:** `bybit-residual-shortfall-flag-reconciliation`
**Related:** BLOCKER-4 (shipped), ADR-054, ADR-042, ADR-017.
**Source classification:** `results/blockers.md` → BLOCKER-5.
**Reviews folded:** BA, Architect, Auditor (all APPROVE-WITH-CHANGES). The auditor's correctness
must-fixes materially reshaped this plan — the earlier "dominant non-clearing stale-shortfall" thesis
was **wrong** and has been removed.

---

## 1. Scope

The BLOCKER-4 re-audit left **311 flagged Bybit ledger points** on `BYBIT:33625378`
(`hasIncompleteHistoryAfter=true`; 205 with `quantityShortfallAfter>0`; 85 uncovered-basis-only). They
are NOT one root — they split into six clusters (§2). This is critical because **some flags are
CORRECT** (genuine gaps) and must stay flagged.

Primary PR (this plan): **5A + 5B + 5F.**
- **5A** — same-second funding-inbound-after-drain ordering (true transient artifact).
- **5B** — shortfall-VALUE decoupling: the flag already clears on recovery but the stuck
  `quantityShortfallAfter` value is never decremented; align them, attributability-gated.
- **5F** — dust tolerance threshold.

Separate follow-up blockers (classified here, fixed elsewhere):
- **5C** — ARB zero-delta cross-key `REWARD_CLAIM` shortfall artifact.
- **5D** — uncovered-BASIS gaps (85 pts: TON early, CUDIS, PAWS, AGLD, WLKN, METH) + prior UNKNOWN.

Investigate, do NOT "fix" by clearing:
- **5E** — genuine `EXTERNAL_TRANSFER_OUT`-origin shortfall gaps (e.g. TON seq 5472, real 15.17-TON
  over-withdrawal). These are correct incomplete-history flags → escalate as extraction/linking.

Out of scope: non-Bybit venues; ADR-054 staking economics; `replaySequence` assignment semantics.

Constraints: backend only; `--skip-frontend` reset; no `--clear-pricing-cache` unless pricing surfaces.

---

## 2. Root cause (DB + code verified; corrected)

### Methodological correction (auditor)
The earlier proof "final qty == Σ`quantityDelta` ⇒ inventory conserved ⇒ flag is stale" is a
**tautology**: on over-disposal the engine caps `quantityDelta` at the available amount and books the
remainder as `quantityShortfallDelta` (never as a delta), so the equality holds for every asset
regardless of real loss. "Flagged AND qty>0" does NOT prove staleness. Flags are driven by **two
independent signals** — `quantityShortfallAfter` (inventory deficit) and `uncoveredQuantityAfter`
(missing basis) — which must be handled separately.

### 5A — same-second funding inbound ordered after the drain (real transient)
`ConfirmedReplayQueryService.corridorContinuityFlowSign` sorts collapsed/corridor `INTERNAL_TRANSFER`
outbound-first by flow sign — wrong when the inbound is a corridor DEPOSIT funding the drain
(post-BLOCKER-4 it lands on umbrella):
```
USDC 2025-03-13T16:23:29 (all transactionIndex=0, same second)
  seq 1099  bybit-collapsed-v1 CARRY_OUT   qBefore 0.024484  → shortfallDelta 10.880618
  seq 1100  BYBIT-CORRIDOR:ARBITRUM CARRY_IN +10.905102 (the funding deposit — sorted AFTER the drain)
  seq 1101  bybit-collapsed-v1 CARRY_IN     +10.905102 (UTA leg)
```
If seq 1100 sorts first the drain is fully covered → shortfall 0. seq 1891 is the same shape
(sf 901.96) — confirm a same-second corridor deposit precedes before relying on 5A for it.

### 5B — shortfall-VALUE decoupling (the real lifecycle defect)
Code-verified: `quantityShortfall` is only ever incremented (`GenericFlowReplayEngine.recordQuantityShortfall`
`add` + setter); nothing decrements it. But `hasIncompleteHistoryAfter` is a **separate** boolean that
`clearResolvedPositionFlags` DOES reset when `qty>0 && uncov==0`. So the two diverge: e.g. **ARB flag
clears at seq 9726 while `quantityShortfallAfter` stays 22.0088**; ~2,246 points carry a stuck value.
The defect is the decoupling — when the flag legitimately clears on attributable recovery, the stuck
shortfall VALUE must clear with it.

### 5C — ARB zero-delta cross-key REWARD_CLAIM artifact (distinct)
ARB umbrella IS conserved (Σdelta 0.1259 == final 0.126); all 60 flags on the **umbrella**; EARN holds
58.44, unflagged. seq 8652 +22.0088 → 8653 −22.0088 stake-to-EARN (covered) → 8661 `REWARD_CLAIM`
(qBefore 0) spuriously stamps `quantityShortfallAfter=22.0088` with `quantityShortfallDelta=0`. The
earlier "flags on a sub-account, umbrella holds balance" was **inverted**. Separate bug — fix the
spurious stamping; do NOT route ARB through 5B.

### 5D — uncovered-BASIS gaps (85 pts)
`quantityShortfallAfter≤0`, `uncoveredQuantityAfter>0`, `basisBackedQuantity=0` reward/earn receipts:
TON early 63 (from seq 970 `bybit-cross-uid-v1`), CUDIS 12, PAWS 7, AGLD 1, WLKN 1, METH 1. Plus prior
UNKNOWN-basis (TON seq 652, METH 1091, MNT 6694). Linking/pricing basis class — untouched by 5A/5B.

### 5E — genuine shortfall gaps (correct flags)
TON seq 5472 (2025-08-15) `EXTERNAL_TRANSFER_OUT`: `qBefore 64.8316`, true outflow 80.0, `sfDelta
15.1684`. **TON:EARN was 0 from seq 1498 (2025-04-15) to seq 6503 (2025-09-17)** → total holdings
64.83 < 80 withdrawn = real gap. The seq 7403 `EARN_FLEXIBLE_SAVING +89.38` "recovery" is unrelated
unstaking months later. Clearing it would MASK a real loss → investigate missing deposit, never
auto-reconcile.

---

## 3. Design (architect) — reject Design A; adopt hybrid **5A + Design B**

- **Design A (enable `preserveCoverage` for collapsed umbrella legs) — REJECTED.** Code-verified:
  `removeFromPositionPreservingCoverage` still calls `recordQuantityShortfall` when the umbrella is
  physically empty, so it cannot prevent the funding-deposit-after-drain trigger; it also flips
  collapsed drains to the proportional-basis path, perturbing basis/AVCO (violates accounting-neutral).
- **Design B (record-then-reconcile) — ADOPTED**, narrowed to "align the stuck VALUE with the
  already-clearing flag," combined with 5A ordering.

### Ordered changes (upstream first)

1. **5A — order the funding corridor DEPOSIT before a same-second drain**
   `ConfirmedReplayQueryService.bybitSameDayTransactionClassOrder`: rank a `BYBIT-CORRIDOR:` inbound
   deposit (`quantityDelta>0`) on the umbrella into the existing settle-first tier
   (`EXTERNAL_TRANSFER_IN = −3`), so it precedes a same-second `bybit-collapsed-v1` drain. Chosen over
   refining `corridorContinuityFlowSign` (runs earlier; cannot reorder the collapsed self-pair — both
   legs stay at class-order 0).
   - **Blocking guard fix:** the method early-returns 0 when `source != BYBIT`; the corridor deposit is
     non-BYBIT source, so the new tier never fires unless the guard is broadened to admit
     `INTERNAL_TRANSFER` rows with `correlationId` starting `BYBIT_CORRIDOR` and `quantityDelta>0`.
     Rename the method accordingly.
   - **Verify FIRST (highest risk):** the tiebreak only fires when `blockTimestamp` AND
     `transactionIndex` are equal. USDC 1099/1100 both have `transactionIndex=0` (confirmed) — good;
     re-confirm for seq 1891 and any other candidate before relying on it.

2. **5B — clear the stuck shortfall VALUE when the flag clears on ATTRIBUTABLE recovery**
   In `GenericFlowReplayEngine`, at the seam where `clearResolvedPositionFlags` resets
   `hasIncompleteHistory` (`qty>0 && uncov==0`) via an attributable carry-restore
   (`restoreToPosition` / `materializePendingInbound` / `attachLateCarryToPendingInbound`), also
   decrement/clear `PositionState.quantityShortfall`. No sweep/repair job over persisted points.
   - **Attributability gate (tight):** clear ONLY when the restoring event shares the shortfall-causing
     continuity — same `corr-family:` / `bybit-earn-carry:` queue, same `correlationId`, OR is the
     paired leg of the same collapsed/corridor pair within a bounded same-timestamp window. Do NOT gate
     on "any positive delta", "qty recovered", or "final qty == Σdelta".
   - **Hard exclusion:** shortfalls introduced by `EXTERNAL_TRANSFER_OUT` with no paired inbound
     (5E, e.g. TON 5472) are NEVER auto-reconciled — they stay flagged.

3. **5C (ARB), 5D (uncovered-basis), 5E (genuine gaps)** — open separate blockers; enumerate in the
   residual register. 5E requires an extraction/linking investigation of the missing TON deposit.

4. **5F — dust tolerance:** concrete threshold (value + units, absolute vs price-scaled); decide
   record-time suppression vs reconcile; assert one ULP above threshold still flags.

---

## 4. Docs & ADR

- `docs/pipeline/cost-basis/` — document the shortfall lifecycle: `quantityShortfall` becomes a
  *reconcilable outstanding shortfall* that clears WITH the flag on attributable recovery; the
  two-signal model (shortfall vs uncovered-basis); the same-second corridor-inbound ordering rule.
- **Extend ADR-042** — corridor-inbound DEPOSIT settles before a same-second `bybit-collapsed-v1` drain.
- **New ADR** — shortfall-flag lifecycle contract + attributability gate + `EXTERNAL_TRANSFER_OUT`
  exclusion; cross-reference ADR-017.
- docs-merged-with-code is an explicit DoD checkbox.

---

## 5. Acceptance (re-audit after `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`)

**Pre-fix baseline (DoD):** persist per-asset snapshot (flagged count, shortfall-only vs
uncovered-only split, per-key Σ`quantityDelta`, AVCO, realized P&L); assert every criterion as a diff.

**Expected-residual table (no zeroing genuine gaps).** Genuine flags MUST remain.

| Cluster | Points (approx) | Post-fix expectation |
|---|---|---|
| 5A same-second ordering (USDC 1099/1891, part of USDT) | ~4–10 | → 0 (drain covered; `quantityShortfallAfter=0`) |
| 5B stuck-value where flag already cleared | ~2,246 value-carrying (non-flagged) | `quantityShortfallAfter`→0 where flag=false & recovery attributable |
| 5C ARB cross-key artifact | 60 | unchanged by this PR (separate blocker) |
| 5D uncovered-basis | 85 + UNKNOWN | unchanged (separate blocker) |
| 5E genuine gaps (TON 5472 …) | remains | **MUST stay flagged** (negative test) |
| 5F dust (ETH/BTC) | ~3 | → 0 (below threshold) |

**Flag-field surface:** assert the complete set — `hasIncompleteHistoryAfter`, `quantityShortfallAfter`,
`uncoveredQuantityAfter`, `hasUnresolvedFlagsAfter`, `unresolvedFlagCountAfter`.

**Attributability / anti-masking assertions:**
- **A1 reconcile-when-attributable:** attributable recovery on key K → `quantityShortfallAfter`
  reduced by min(S, restored); flag+value both clear when fully covered.
- **A2 persist-when-unattributable:** unrelated inflow (different correlation / no queue link /
  different key) → shortfall value AND flag unchanged.
- **A3 accounting-neutral:** ZERO change to {cumulative `quantityDelta`, AVCO, realized P&L,
  `basisHeldUsd`} within |Δ| ≤ 1e-6 per asset (no "or explained" escape).
- **A4 EXTERNAL_TRANSFER_OUT genuine gap:** TON seq 5472 stays flagged with `quantityShortfallAfter`
  15.17 through the final point (the masking negative test).
- **A5 no cross-key leak:** reconcile on key K never clears a flag/value on key K′ (guards ARB).
- **A6 ARB untouched:** ARB umbrella still 60 flagged / value 22.0088 after this PR (owned by 5C).

**Quantity conservation:** per-key Σ`quantityDelta` unchanged pre/post (fix touches only shortfall +
flags, never quantity/basis).

**Anchors:** USDC 1099 corridor deposit sorts before collapsed drain, `quantityShortfallAfter=0` onward;
BLOCKER-4 cmETH 2025-04-17 convert stays `quantityShortfallAfter=0`.

**Determinism:** two `--skip-frontend` runs → identical flagged counts and identical USDC 1099 ordering.

---

## 6. Risks

- **Masking a genuine gap (highest):** 5E (TON 5472) must stay flagged; the gate excludes
  `EXTERNAL_TRANSFER_OUT`-origin and unrelated recoveries. A4 is the guard test.
- **Design A trap:** rejected (doesn't prevent trigger; not basis-neutral).
- **5A tie assumption:** verify `transactionIndex` equality per candidate before relying on the
  comparator.
- **Two-signal conflation:** 5A/5B touch `quantityShortfall` only; the 85 uncovered-basis flags (5D)
  and ARB (5C) must be explicitly excluded and remain flagged.
- **Scope creep:** 5C/5D/5E are separate blockers; keep primary PR to 5A+5B+5F.

---

## 7. Test plan (named scenarios)

- **`ConfirmedReplayQueryServiceTest`** — corridor-inbound deposit sorts before same-second collapsed
  drain (USDC 1099); collapsed self-pair OUT-before-IN unchanged; corridor inbound NOT funding a
  same-second drain → unchanged; FEE-role / zero-delta leg not reordered; ≥3-leg cluster total/stable;
  BLOCKER-4 anchors intact.
- **`GenericFlowReplayEngine` / replay integration:**
  1. Attributable same-second collapsed/corridor recovery → shortfall value + flag both clear (A1).
  2. Partial then full attributable recovery across multiple paired inbounds → clears incrementally.
  3. **NEGATIVE — `EXTERNAL_TRANSFER_OUT` genuine gap (TON 5472 shape):** EARN empty, over-withdrawal,
     later UNRELATED unstake → stays flagged, value 15.17 persists (A2, A4).
  4. **NEGATIVE — ARB cross-key REWARD_CLAIM artifact:** stays flagged/value after 5B (A5, A6).
  5. **NEGATIVE — uncovered-basis inbound (`uncov>0`):** stays flagged after 5B.
  6. Dust boundary triple (5F).
- **`AvcoReplayServiceTest`** — USDC corridor-fund-drain anchor: drain covered, zero residual shortfall,
  AVCO/P&L unchanged (A3); per-key Σdelta neutrality.
- Full `:backend:core:test` green.

---

## 8. Reporting / DoD artifacts

- **`results/blockers.md`** — BLOCKER-5 → RESOLVED for 5A/5B/5F with post-reset counts; open follow-up
  blockers for 5C (ARB), 5D (uncovered-basis), 5E (genuine TON gap); amend BLOCKER-4 forward-reference.
- **`results/warnings.md`** — expected warnings delta; no new warning type; no dust-suppression warning
  that hides material shortfall.
- **`results/reconciliation.md`** — per-asset shortfall reconciliation table (flagged before/after,
  per-key Σdelta) proving flags cleared without moving inventory/basis.
- **Residual register (new)** — every still-flagged post-fix point labelled (5C / 5D / 5E-genuine /
  dust) — operationalizes no-silent-masking.
