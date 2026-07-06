# Implementation Plan — Net AVCO carry conservation + Bybit-umbrella Net double-add

**Slug:** `net-avco-carry-conservation-and-umbrella-double-add`
**Date:** 2026-07-02
**Audit reference:** Phase-1 audit (this cycle) → `results/blockers.md`, `results/accounting-failure-analysis.md`,
`results/required-changes.md`. Universe `df5e69cc…`, current replay.
**Phase:** 2 (plan). **Stop before Phase 4/5 pending user approval + Phase-3 reviewer sign-off.**

---

## Symptoms (user)

1. **ETH Net AVCO == Tax(Market) AVCO** (~$2,677 both). User expects earned rewards / withdrawn LP fees to
   pull **Net below** Tax.
2. **LINK / LTC / DOGE Net AVCO diverges very strongly from Tax** ("I never bought at these prices").
3. Tx `0xf7f8…3132430` (0.862092 cmETH) looked like it was booked at ~$4.83. — **Audit: CORRECT-as-is**,
   the $4.83 was the Pendle leg unit price; cmETH is booked at $3,945/unit ($3,401 carried LP basis, below
   FMV $4,383). No defect. **Not in scope.**

## Root causes (audit-verified)

### Defect A — Net-lane basis non-conservation on IN legs (ETH Net==Tax)
Stage: `cost_basis`/`move_basis` (replay carry). ETH **does** accrue $0-net reward/fee income booked
correctly at acquisition (LP_EXIT/ACQUIRE +$1,527.77, LP_FEE_CLAIM +$162.43, REWARD_CLAIM +$10.87 ≈
**$1,701** on ~0.60 ETH, all net Δ = $0). But every IN / `REALLOCATE_IN` / `CARRY_IN` leg **re-seeds net
basis from the tax basis instead of transporting the carried (lower) net basis**. This cancels the reward
discount so terminal Net $2,676.99 ≈ Tax $2,677.30.

**The leak is systemic and multi-family, not WRAP-only (auditor Phase-3 refinement).** WRAP is the dominant
*same-tx* contributor (349 txs: Σtax residual $0.00 vs **Σnet residual +$1,025.26**; the 2025-11-17 anchor
alone creates **+$1,020.48**, ETH out net avco $1,714 → WETH in net avco $3,572), but it is only ~$1,025 of
the ~**$1,672** total ETH reallocation heal. The remaining ~$647 comes from cross-corridor `CARRY_IN` legs:
BRIDGE_OUT/IN ~$332, LP_ENTRY ~$272, plus INTERNAL_TRANSFER / LENDING / EARN REALLOCATE. Same defect proven
on other families booking net-from-nothing: **USDC +$28.00, AVAX +$12.70, ARB +$0.77**. So the corrected
ETH terminal Net is only exactly knowable after a net-conserving re-replay; the full-retention floor is
**Net ≈ $2,243** (net basis $10,492.80 − $1,701 = $8,791.80 over 3.9192 covered), rising toward Tax under
partial disposal → true range **~$2,240–$2,600**.

**Mechanism — primary seam is the APPLY site, not construction defaults (architect Phase-3 refinement):**
1. **Apply-site collapse (dominant).** Almost every IN-leg restore calls the tax-only 5-arg overload
   `GenericFlowReplayEngine.restoreToPosition(qty, position, cost, uncovered, avco)` (L471–479), which
   hard-forces `netCost = cost`; callers pass `carry.costBasisUsd()`/`carry.avco()` and **discard**
   `carry.netCostBasisUsd()`/`netAvco()` (e.g. `TransferReplayHandler` L320/607/684/886/917/1031/1070/1140/
   1214; `LpReceiptEntryReplayHandler` L254; `LpReceiptExitReplayHandler` L158;
   `FamilyEquivalentCustodyReplayHandler` L263; `GmxLpEntryReplayHandler` L174/214;
   `GenericAsyncLifecycleReplayHandler` L118/194). Only one caller uses the net-aware 6-arg overload.
2. **Late-inbound refine collapse.** `applyAuthoritativeLateInboundCarryBasis` (L648–650) writes a single
   `carryBasisUsd` to **both** tax and net lanes on the pending-inbound refine path.
3. **Construction defaults (secondary, source-side).** `CarryTransfer`'s compact constructor defaults
   `netCostBasisUsd = costBasisUsd` / `netAvco = avco` (L37–44) and the net-less constructors (L63–101)
   silently book tax→net — mostly for the `ContinuityCarryService` bridge/orphan builders. The main
   `removeFromPosition`, `ContinuityBucket.take/add`, and `mergeCarryTransfers` already carry true net.

Fixing construction defaults alone will **not** close AC-A1: the apply site must transport net 1:1.
**Correct orphan fallback (preserve):** for a source-less orphan CARRY_IN or a fresh priced acquire (no
reward-discount evidence), `net = tax` is correct — mirror `applyInboundShortfallSpotFallback` (L735–736).

### Defect B — Bybit-umbrella headline Net double-add (LINK/LTC/DOGE Net ≈ 2× Tax)
Stage: `verification` (read-model). The per-bucket **ledger is correct**: LINK Tax $11.98 / Net $11.96,
LTC $55.38 / $55.38, DOGE $0.18 / $0.18 — Net ≈ Tax because these assets have ~no $0-net reward income.
The headline is wrong because `AssetLedgerQueryService.currentStateView` (Bybit-umbrella branch) adds the
**tax** total into the net accumulator **and then** adds the net total again:

```371:376:backend/src/main/java/com/walletradar/costbasis/application/AssetLedgerQueryService.java
                totalCostBasisUsd = totalCostBasisUsd.add(scaled.totalCostBasisUsd(), MC);
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(scaled.totalCostBasisUsd(), MC);   // <-- BUG: tax into net
                netTotalCostBasisUsd = netTotalCostBasisUsd.add(
                        umbrella.netTotalCostBasisUsd().multiply(scale, MC),
                        MC
                );
```

Result: umbrella Net AVCO ≈ 2× (LINK ~$23.9, DOGE ~$0.36, LTC ~$110.8). `fullSessionCurrentView` is
already correct. **Fix = delete the stray line 372** (`netTotalCostBasisUsd += scaled.totalCostBasisUsd()`).
Read-model only, **no replay reset**.

## Scope

- **In scope:** Defect A (net-carry conservation, replay/accounting) + Defect B (one-line read-model).
- **Deferred (known, diagnoses preserved):** LTC live 1.2611 vs ledger 0.7501 = the **0.511** netted-split
  Bybit-earn corridor gap; LINEA `ALINWETH`/WETH RC-E3 receipt-carry remnant (~0.0346 ETH, <$135).
- **Not touched:** pricing policy, classification, linking (beyond the deferred items), the move-basis
  chart series (ADR-045 already correct).

## Changes (ordered — quick read-model win first, then the accounting fix)

### Change 1 — Defect B (read-model, backend-only, no replay) — fast-trackable
Delete the stray `netTotalCostBasisUsd = netTotalCostBasisUsd.add(scaled.totalCostBasisUsd(), MC);` (line
372) in the Bybit-umbrella branch of `AssetLedgerQueryService.currentStateView` — auditor confirmed this is
the **only** tax-into-net double-add (on-chain branch L284–290 and `fullSessionCurrentView` L217–224 are
already correct). The correct net remains `umbrella.netTotalCostBasisUsd() * scale` (L373–376). Add a
regression test asserting umbrella Net AVCO == per-bucket net (LINK ≈ $11.96, DOGE ≈ $0.18, LTC ≈ $55.38,
not 2×) and `fullSessionCurrentView` parity. **This does NOT fix ETH Net==Tax** (that is Change 2); it only
corrects the LINK/LTC/DOGE headline. **Note (LTC):** Change 1 fixes the *relative* LTC lanes (Net≈Tax≈
$55.38) but the live-qty 1.2611 vs ledger 0.7501 **0.511 over-coverage** dilutes both lanes equally and
remains a separate deferred earn-corridor issue — LTC absolute AVCO stays slightly off until that work.

### Change 2 — Defect A (replay net-carry conservation) — all-corridor
Enforce the invariant **net basis follows quantity on every IN leg, transported 1:1 from source — never
re-derived from tax** (except the source-less-orphan / fresh-acquire fallback where net = tax is correct).

**Primary — unified net-aware apply seam:**
- Add `GenericFlowReplayEngine.restoreToPosition(CarryTransfer carry, PositionState position)` that credits
  **both** lanes from the carry — tax from `costBasisUsd`/`avco`, **net from `netCostBasisUsd`/`netAvco`** —
  applying the peg floor to each lane independently (as the existing 6-arg overload does). Route **every**
  IN-leg handler through it so a lane can no longer be structurally dropped: `TransferReplayHandler`
  (WRAP/UNWRAP/REALLOCATE/CARRY_IN), `LpReceiptEntryReplayHandler`, `LpReceiptExitReplayHandler`,
  `FamilyEquivalentCustodyReplayHandler`, `GmxLpEntryReplayHandler`, `GenericAsyncLifecycleReplayHandler`.
- Add a **net-basis parameter** to `applyAuthoritativeLateInboundCarryBasis` (L648–650) so the pending-
  inbound refine path carries source net, not tax, into the net lane. (Bridge late-carry ADR-020: net and
  tax become authoritative at the same refine moment — no ordering window; provisional materialization
  already applies the same provisional to both lanes.)
- **Scope: ALL corridor IN classes** — WRAP/UNWRAP **and** bridge late-carry, LP receipt entry/exit,
  lending deposit/withdraw, internal-transfer carry, earn REALLOCATE (auditor proved all book net=tax).

**Secondary — source-side construction + compile-time safety (required, not optional):**
- Fix the `ContinuityCarryService` bridge/orphan builders to pass true source net.
- **Delete** the two general net-less `CarryTransfer` constructors (`CarryTransfer.java` L78–89, L91–101)
  so a "known" carry (from a `PositionState`/`ContinuityBucket`) must pass explicit net args → the ETH WRAP
  bug becomes a **compile error**, not a silent default. **Keep** the `pendingInbound(...)` /
  `pendingInboundUnmaterialized(...)` factories (net legitimately unknown until refine); keep the compact
  constructor's `net=null → net=tax` only reachable via those factories / source-less orphans.

**Runtime guard (required):** add a finalize-time conservation assertion — for any closed intra-family
round-trip on one position key (WRAP↔UNWRAP, spot↔receipt, REALLOCATE_IN↔OUT), `|Σ netCostBasisDelta| ≤
dust`, exactly as tax conserves today. This turns AC-A1 into a runtime invariant preventing silent
recurrence (checks deltas, so it composes across cross-asset/cross-unit carries; central net *derivation*
is rejected — it can't compose across WRAP asset/unit changes or LP N-asset merges).

- Requires a **net-conserving re-replay** (`--skip-frontend`).

## Documentation (Phase 4, before code)

- `docs/pipeline/cost-basis/02-avco-rules.md` + `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` —
  state the net-lane carry invariant (net basis transported on IN legs; `Σ netCostBasisDelta = 0` on
  round-trips) alongside the existing tax invariant.
- ADR: amend **ADR-040 (dual cost basis)** to make the net-carry conservation rule explicit (IN legs carry
  source net basis; rewards/fees keep Net below Tax). ADR change → explicit user approval.
- If terminology confirmed: note "Market AVCO" as the UI label for the Tax lane (already applied in UI).

## Acceptance criteria

**Primary gates are the invariants; dollar values are non-blocking sanity guardrails (BA + auditor).**

- **AC-A1 (conservation — family-wide, runtime):** for **every** family that wraps/reallocates (not just
  ETH — USDC/AVAX/ARB also leak today), `|Σ netCostBasisDelta| ≤ dust` on a single position key over closed
  round-trips; same-tx conserving types (WRAP/UNWRAP/LENDING/STAKING) show per-tx `Σ netΔ = 0`, cross-tx/
  cross-chain corridors conserve on the position key. Enforced by the finalize-time guard. WRAP net residual
  0.00 (was +$1,025.26).
- **AC-A2 (ETH Net below Tax — floor + direction, NOT a hard band):** (a) Net **materially below** Tax
  (Δ ≥ ~$100/ETH); (b) Net **≥ the full-retention floor ≈ $2,243**; (c) exact terminal Net reconciles
  against the AC-A1 conservation delta (a correct re-replay may land anywhere in ~$2,240–$2,600 — do **not**
  fail a value in-range). Tax terminal unchanged at ~$2,677.30.
- **AC-A3 (global invariant gate):** **`Net AVCO ≤ Tax AVCO` for every asset/family** in **both**
  `currentStateView` and `fullSessionCurrentView` after Change 2 (inclusive `≤` so no-reward assets with
  Net==Tax do not false-fail). Any asset with Net > Tax is a hard failure.
- **AC-A4 (no underflow):** **Net basis ≥ 0 and Net AVCO ≥ 0** for every position (the carry-transport must
  not drive net below zero).
- **AC-A5 (no collateral regression):** covered qty unchanged; **tax lane bit-unchanged** (tax terminals for
  all families identical pre/post); quantities/coverage unchanged.
- **AC-B1:** Headline umbrella Net AVCO == per-bucket net for LINK/LTC/DOGE (≈ $11.96 / $55.38 / $0.18),
  not ~2×; `fullSessionCurrentView` and `currentStateView` agree.
- **AC-B2:** No change to assets that legitimately have Net < Tax (reward-bearing) beyond removing the
  double-add.
- **Evidence (Phase 6):** a **before/after full-family Net & Tax AVCO diff** (both views) as sign-off
  artifact, documenting the intended LINK/LTC/DOGE ~2× headline drop and ETH Net-below-Tax.
- **Auditor sign-off (Phase 6):** re-reconcile ETH Net/Tax and LINK/LTC/DOGE headline vs ledger.

## Risks

- **Change 2 is an accounting-engine change requiring re-replay** — historically the highest-risk area
  (prior earn-corridor reverts). Mitigate: apply the net-carry as a strict transport (mirror the proven
  tax-carry path), add per-round-trip conservation assertions, and verify no tax-lane / quantity movement.
- **Blast radius:** the net-less `CarryTransfer` constructors are used widely; changing defaults could shift
  many families' Net. Guard with AC-A3 / AC-B2 and a full family Net/Tax diff before vs after.
- **Change 1 is near-zero risk** (one stray line, read-model, no replay) and can be fast-tracked/shipped
  first to fix the LINK/LTC/DOGE headline immediately.
- Deferred LTC 0.511 / LINEA RC-E3 unchanged.

## Rebuild classification

- **Change 1 (Defect B):** `--backend-only` (read-model, no replay).
- **Change 2 (Defect A):** `--skip-frontend` (net-conserving renormalization/replay).

## Phase gate

Phase 1 complete. **Phase 3 review complete and passed** — financial-auditor, business-analyst, and
system-architect all **APPROVE-WITH-REVISIONS**; all revisions folded into this Rev 2 (apply-site primary
seam + unified `restoreToPosition(CarryTransfer, position)`; all-corridor scope; delete net-less
constructors for compile-time safety; finalize-time `Σ netΔ=0` guard; family-wide AC-A1; `Net ≤ Tax` global
gate AC-A3; `Net ≥ 0` guard AC-A4; AC-A2 floor+direction not hard band; before/after full-family diff
evidence). Plan is **Phase 2 Rev 2 — reviewer-approved**. Next: **STOP pending USER approval.** On approval:
Phase 4 (docs incl. ADR-040 amendment — needs explicit user OK), Phase 5 (Change 1 then Change 2), rebuild
(`--backend-only` then `--skip-frontend`), Phase 6 auditor sign-off + before/after diff.

Change 1 (delete line 372) is a proven low-risk one-line read-model fix (`--backend-only`, no replay) the
user may fast-track immediately to remove the LINK/LTC/DOGE ~2× headline; it does **not** fix ETH Net==Tax.
