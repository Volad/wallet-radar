# Implementation Plan — Move-Basis AVCO Chart Lane-Consistency (ETH needle)

**Slug:** `eth-move-basis-avco-chart-lane-consistency`
**Date:** 2026-07-02 · **Rev 2** (incorporates Phase-3 reviews: financial-auditor [REVISE→addressed], system-architect, business-analyst)
**Audit reference:** `results/blockers.md` (Replay #11 — ETH AVCO spike), `results/required-changes.md` (RC-E1/E2/E3), `results/accounting-failure-analysis.md` addendum.
**Phase:** 2 (plan), revised after Phase-3 review. **Stop before Phase 4 (docs) + Phase 5 (code) pending user approval + auditor re-sign-off.**

---

## Symptom (user, with screenshots)

The ETH move-basis chart AVCO line jumps sharply on tiny events — e.g. `AVCO before $1,675.68 → after
$2,724.45` on a dust `BRIDGE_OUT`/`GAS_ONLY`; `$1,904.19 → $2,508.55` on a bridge exit. User reads this as
a cost-basis error and asks about tx `0x2108…`.

## Root cause (Phase-1 auditor, verified to tx level; confirmed in Phase-3)

**The needle is a read-model / presentation artifact, not a family basis-conservation defect.** The
authoritative terminal **FAMILY:ETH covered-weighted AVCO is stable at $2,677.30 (Tax) ≈ $2,676.99 (Net)**,
3.9192 of 3.9223 ETH covered (uncovered 0.08 %), basis **$10,492.80**.

- **E1 (PRIMARY, stage `verification`):** the chart is fed a **per-`accountingAssetIdentity`
  carry-forward AVCO** from `TimelineAvcoAuthority.resolve(...)` (`AssetLedgerQueryService` timeline loop,
  `avcoResolution.avcoAfterUsd()` → each `TimelineEntryView`). The frontend then chains a **global**
  `before` against that **per-identity** `after` (`reconcileMarkerAvcoSeries`). On a dust event the
  resolver switches which sub-pool it reports, so the line hops lanes: proven $1,675.68 = BASE-WETH
  sub-pool, $2,724.45 = Bybit/Arbitrum-ETH sub-pool. **Not a basis error.**
- **E2 (false positive):** "$0-basis" cmETH/Bybit `CARRY_IN`s are qty-leg/basis-leg splits; basis carries
  on the sibling point. Conserved.
- **E3 (real, small, `move_basis`/`cost_basis`):** `0x2108` $0 bridge-in is a *symptom* — its source pool
  was itself uncovered; WETH from `LP_EXIT_SETTLEMENT` (seq 9248/9249) does not reattribute LP-RECEIPT
  basis into FAMILY:ETH. Terminal **under-coverage** ~0.0031 ETH (~$100–150, 0.08 %). **Deferred.**
- **E4 (stage `replay`):** Bybit-collapsed credit-before-debit ordering; conservation-neutral.

### CRITICAL Phase-3 correction — which estimator the chart must plot

Rev-1 wrongly claimed `AssetLedgerQueryService.AggregatedState.avco()` "is exactly the stable ~$2,677
series." **It is not.** There are two distinct estimators; the auditor verified both against Mongo
(universe `df5e69cc…`, Replay #11, 3,925 FAMILY:ETH points):

| Estimator | Definition | Terminal Tax | Terminal basis | Intermediate behavior |
|---|---|---|---|---|
| **Method A** `AggregatedState.avco()` | `Σ costBasisDelta (cumulative) / coveredQty` | **$2,909.69** ❌ | $11,403.63 | **245 events >5 %**, up to **+332 %** (mix of artifacts + non-conservation) |
| **Method B** per-bucket snapshot | `Σ coveredᵢ·avcoᵢ / Σ coveredᵢ` over **live per-bucket states** | **$2,677.30** ✅ | $10,492.80 | **142 jumps >5 %**, peak **~+101 %** — but all **economically real** |

Method A's delta stream is **not basis-conserving** (disposals + corridor CARRY/REALLOCATE half-cycles +
E3 injections leave ~$911 residue), so it overstates by +$233/ETH (+8.7 %) **and** creates a *new, larger*
family of needles than E1. **The chart must plot Method B**, whose numerator/denominator are always the
current covered basis and covered qty of real buckets — deterministic and heuristic-free.

**IMPORTANT — Method B is NOT a flat line (auditor-verified).** It eliminates the *false* per-identity
lane-hops (E1) and the Method-A non-conservation artifacts (245→142 jumps, peak 332 %→~101 %, terminal
correct at $2,677.30), but the covered-weighted line **still shows large, economically-real swings**. Root
cause (a genuine design consequence of ADR-017, not a bug): most ETH spends its life locked in
LP positions / bridged / in receipt tokens, which live in the **excluded** `FAMILY:LP_RECEIPT` view. So the
in-scope spot-ETH covered quantity oscillates between ~0.1 and ~1.0 ETH; when the bulk of ETH moves in/out
of the excluded view and the composition flips between the low-basis BASE-WETH lane (~$1,849) and
higher-basis lanes (~$3,715), the covered-weighted average legitimately swings ~2×. **Exemplar:**
`2025-09-10 LP_EXIT +0.862 ETH → $1,849 (cov 0.107) → $3,715 (cov 0.969)`. This is the honest metric
working correctly — the correct replacement for the crude lane-hop, not a smooth line. A genuinely smooth
"total ETH cost-basis" line would require including LP-RECEIPT/locked basis in the family series (RC-E3
territory) — decided there, not silently expected from Change 1.

## Scope

- **In scope:** the FAMILY move-basis chart AVCO series (Net primary + Tax secondary, ADR-040) on the
  asset-ledger page — family-generic, ETH is the exemplar. RC-E1 (primary).
- **Deferred to separate plans (backlog pointers, diagnoses preserved):**
  - **RC-E3** LP-exit/native basis reattribution (~0.08 % under-coverage; separate `cost_basis` plan;
    needs `--skip-frontend` renorm; coordinate with WIP `NativePoolReconciliation*` /
    `NativeSettlementRecovery` / `ZeroCostAcquisitionSupport`). **Phase-6 additions to this backlog
    (surfaced, not caused, by the read-model change; immaterial to the $2,677.30 terminal):**
    (i) 2025-09-20 LINEA `LENDING_DEPOSIT` books the `ALINWETH` receipt at replay-stored avco **$3.23** vs
    ~$3,800 (entry-side receipt-basis-carry defect, ~$368 transient chart move); (ii) 2 native gas-dust
    buckets with `covered > 0 @ avco $0` (`NATIVE:BASE` 1.79e-5 ETH, `NATIVE:OPTIMISM` ~1e-7 ETH; ~$0.05,
    <$0.01 aggregate drag).
  - **LTC 0.511** netted-split Earn subscribe — proven `EVIDENCE_PRESENT_UNUSABLE` type-model gap; prior
    durable fix reverted with broad blast radius (LDO 338→580, ONDO 401→689, ETH $2674→$2618,
    `BYBIT_ASSET_TOTAL_MISMATCH`). Needs conservative multi-leg corridor-pairing redesign.
  - **RC-E2** qty-leg/basis-leg coalescing — redundant once E1 lands (Method B already nets them); optional
    hygiene only.
  - **Residual line volatility (design decision, not a defect):** the real swings under Method B are driven
    by ADR-017 **excluding LP-RECEIPT / locked ETH basis** from the family series. If the product goal is a
    genuinely smooth "total ETH cost-basis" line, that requires *including* locked/LP-RECEIPT basis in the
    family AVCO — same territory as RC-E3 — and must be decided there, **not** silently expected from
    Change 1. Tracked as an open product/architecture question.
- **Explicitly NOT touched:** replay engine, cost-basis operators, corridor pairer, conservation guards —
  RC-E1 is read-model only; underlying `asset_ledger_points` basis is already correct.

## Changes (ordered)

### Change 1 — RC-E1 (PRIMARY): plot the Method B per-bucket covered-weighted family series
`backend/.../costbasis/application/AssetLedgerQueryService.java` — in the timeline loop
(`~L85–137`), add a **per-bucket-state accumulator** alongside `AggregatedState`: track each live bucket's
latest `avcoAfter` / `netAvcoAfter` and `basisBackedQuantityAfter` (covered qty) as `displayEvents` are
applied. After each event compute the chart series as:
- `avcoAfterUsd = Σ coveredᵢ·avcoᵢ / Σ coveredᵢ` (Tax), `netAvcoAfterUsd = Σ coveredᵢ·netAvcoᵢ / Σ coveredᵢ`
  (Net), over live buckets (spot-family-filtered, LP-RECEIPT / non-ETH excluded as today).
- **ADR-031 undefined:** when `Σ coveredᵢ ≤ 0`, emit `avcoAfterUsd = null` (line breaks; never $0).
- Set `avcoKind = (series == null ? UNAVAILABLE : PRIMARY_FLOW)`. **Never emit `FAMILY_ROLLUP`** — the
  frontend `buildYProjection` filters that kind out and would empty the chart.
- **Emit `avcoBeforeUsd` / `netAvcoBeforeUsd`** on `TimelineEntryView` = the prior event's Method-B `after`,
  so the backend owns the whole before/after contract (single continuous series) and the frontend cannot
  re-introduce lane artifacts.

**Sequencing / heuristic removal:** implement Method B first; **then** retire `TimelineAvcoAuthority` from
the chart path (median-outlier rejection, `capCarriedAvco`, per-identity carry-forward, `medianSpotAvco*`
precompute, `lastAvcoByAssetIdentity`/`lastNetAvcoByAssetIdentity` trackers). Method B produces no *false*
spikes (no lane-hop, no non-conservation artifact), so the outlier/cap heuristics — which existed to
suppress those false excursions — are no longer needed on the primary line; the residual swings under
Method B are **real** and must not be capped. Do **not** drop the heuristics while any delta-sum path is
still wired.

**Accumulator implementation notes (auditor):** key buckets by the existing `BucketKey`
`(walletAddress, networkId, accountingAssetIdentity)`; within a multi-point event take the **last** point
per bucket; read the raw stored `avcoAfterUsd` / `netAvcoAfterUsd` and `basisBackedQuantityAfter` (not the
ADR-031 null-filtered variant — covered≈0 buckets contribute ~0 to both numerator and denominator);
`coveredᵢ = min(basisBackedQuantityAfter, qtyAfter)`; order by `(blockTimestamp, transactionIndex,
replaySequence)`. **No secondary per-identity series** (architect + auditor
agree: no product consumer). Per-lane / per-pool forensic detail remains available in the expandable event
rows and tooltip (raw `LedgerPointView` — a distinct, labelled surface, not the plotted family line).

### Change 2 — RC-E1 frontend (MANDATORY, not optional)
`frontend/.../features/asset-ledger/asset-ledger-page.component.ts`:
- **`reconcileMarkerAvcoSeries` (~L2170–2211):** remove the synthetic null carry-forward
  (`shouldCarryForwardTax/Net` for `CARRIED_FORWARD` / `LP_ENTRY` / `SPONSORED_GAS_IN` / `REALLOCATE_OUT` /
  `CARRY_OUT`). Under Method B a backend `null` = drained-family undefined AVCO and the line **must break**
  (ADR-031); the carry-forward would paper over it. Keep only pass-through chaining (`before = prev.after`,
  or delete the method entirely if backend emits `before`).
- **`buildYProjection` (~L2219):** ensure emitted `avcoKind` is never `FAMILY_ROLLUP` (else the y-axis
  collapses); prefer keeping `PRIMARY_FLOW`/`UNAVAILABLE`.
- Verify `buildMarkers`/`collapseMatchedMarkers` before/after become correct-by-construction under a
  continuous series (they should; delete redundant derivation if backend emits `before`).

## Documentation (Phase 4, before code)

- **New superseding ADR** (supersedes ADR-017's "family rollup numerators are never chart sources"):
  record that the Method-B series is materially different from the rollup ADR-017 rejected — it is
  (i) spot-family-**filtered** (LP-RECEIPT / cross-asset excluded), (ii) **covered-weighted**, (iii)
  **uncovered-aware**, (iv) per-bucket-snapshot (not cumulative-delta). State the rejection-no-longer-applies
  rationale so a future engineer does not revert to the per-identity authority. (ADR change → user approval.)
- `docs/frontend/move-basis.md` + release note — list the **four user-facing changes**: (1) line no longer
  dips to $0 on sub-pool drain — it now *breaks* (null gap); (2) terminal value shifts to the family
  aggregate ≈ $2,677 (was whichever sub-pool the resolver last reported); (3) **false lane-hop needles gone**,
  but the line still shows large **economically-real** covered-weighted swings whenever LP-lock / bridge /
  receipt legs move the bulk of ETH in/out of the excluded `FAMILY:LP_RECEIPT` view (per-pool detail moves
  to event rows/tooltip) — this is expected, not a bug; (4) tooltip/event-row per-pool AVCO is labelled
  distinct from the plotted family line.
- `docs/pipeline/cost-basis/02-avco-rules.md` / `docs/reference/ledger-points-and-basis-effects.md` — chart
  uses Method-B per-bucket covered-weighted family AVCO.
- Backlog pointers: create/append tracked items for **RC-E3** and **LTC 0.511** preserving their audit
  diagnoses (not narrated away).

## Acceptance criteria (revised per business-analyst)

- **AC-1a (zero-basis-delta stability — re-scoped after Phase-6):** for `GAS_ONLY`/`CARRY_OUT` events with
  `|qtyΔ| < 0.001` that are **single-bucket / pure non-composition** (no covered-basis reallocation across
  distinct in-family buckets), the plotted AVCO changes by `< $1` (or `< 0.1 %`). **Excluded:** net-dust
  grouped events that straddle a bucket boundary and reallocate covered basis across in-family buckets at
  genuinely different avcos (WEETH/AWETH/CMETH/WETH) — those are the AC-7b real-swing class landing on a
  dust grouped event and are financially correct, not regressions. (Phase-6 found 46 net-dust ≥$1 moves, of
  which ~43 are this legitimate class; the original flat-<$1 wording over-covered them.)
- **AC-1b (self-chaining):** for every timeline entry, `avcoBeforeUsd == previous entry's avcoAfterUsd`
  (single continuous family series).
- **AC-1c (terminal):** plotted terminal Tax AVCO = **$2,677 ± $10**, Net ≈ $2,677; and **chart terminal ==
  summary/headline Net AVCO** for the family.
- **AC-1d (regression fixtures):** the exact reported events render continuous — `$1,675.68→$2,724.45`,
  `$1,904.19→$2,508.55`, and the `0x2108` family — no lane-hop.
- **AC-2 (null-break render):** a fully-drained family yields a **gap** (not plotted $0, not a crash);
  re-acquisition starts a new segment; summary terminal-AVCO shows "n/a" when family ends drained.
- **AC-3 (concurrent lanes):** with two lanes held (on-chain WETH + Bybit ETH), when one drains the
  aggregate line stays **continuous** (weight shifts smoothly, no reset) — dedicated fixture.
- **AC-4 (Net/Tax divergence):** for a reward-bearing family (Net << Tax), both lines plot, both covered-
  weighted and continuous, Net sits below Tax by cumulative reward-income basis. (For ETH the two nearly
  coincide, reward < 0.5 %.)
- **AC-5 (multi-wallet):** the series equals the summary AVCO regardless of wallet count.
- **AC-6 (tooltip/row consistency):** plotted line, hover tooltip, and summary reconcile — or the per-event
  per-pool value is explicitly labelled as distinct from the family line.
- **AC-7 (determinism):** the primary line is a pure fold over ordered points — **no** median/outlier/cap
  heuristics; re-render identical.
- **AC-7b (real-swing tolerance — NOT a regression):** the primary line **may** show large legitimate
  covered-weighted swings (auditor-verified: 142 jumps >5 %, peak ~+101 %, e.g. `2025-09-10 LP_EXIT`
  $1,849→$3,715) whenever LP-lock / bridge / receipt legs move the bulk of ETH in/out of the excluded
  `FAMILY:LP_RECEIPT` view. These are **economically real**, must remain **self-chaining** (AC-1b) and
  reconcile to the $2,677.30 terminal (AC-1c), and must **not** be flagged as failures or capped in
  Phase-6 QA. Only *false* excursions (per-identity lane-hop; Method-A non-conservation) are regressions.
- **AC-8 (clamp guard):** confirm no family reaches `covered > 0` with `totalCostBasisUsd`/
  `netTotalCostBasisUsd` floored-to-0 (a false $0 dip); any occurrence is an **upstream conservation
  defect** to fix, not to re-mask.
- **AC-9 (uncovered honesty):** Change 1 does **not** hide residual uncovered — family uncovered % (~0.0031
  ETH for ETH) is still reported truthfully in summary/pills (guards the E3 deferral).
- **AC-10 (no regression):** other families' chart series (SOL, LINK, LDO, ONDO, DOGE, MNT, BTC) remain
  sensible; headline dashboard AVCO (live-anchored) unchanged; tests updated/green
  (`TimelineAvcoAuthorityTest` retired/replaced, `AssetLedgerQueryServiceTest`,
  `AssetLedgerQueryServiceGasOnlyAvcoTest`).
- **Auditor sign-off (Phase 6):** re-reconcile the plotted ETH series against the authoritative Method-B
  reconstruction ($2,677.30 / basis $10,492.80).

## Rebuild classification (corrected per architect)

- **RC-E1: `--backend-only` + `--frontend-only`** (Method B is a read-model fold over stored per-bucket
  `avcoAfter`/`basisBackedQuantityAfter`; `asset_ledger_points` unchanged, **no replay reset**). Do **not**
  use `--skip-frontend`. The frontend change (Change 2) is mandatory.
- **RC-E3 (deferred): `--skip-frontend`** (basis attribution / renormalization).

## Risks

- **Method A trap:** plotting `state.avco()` would terminate at $2,910 and needle >300 %. Mitigated —
  plan mandates Method B (per-bucket snapshot).
- **Clamp-induced false $0 dips** exposed by heuristic removal — guarded by AC-8; treat as upstream defect.
- **Chart-empty regression** if `avcoKind=FAMILY_ROLLUP` slips in — guarded by AC-1/Change 2.
- **ADR drift:** future engineer reverting to the per-identity authority — mitigated by the superseding ADR
  recording why the covered-weighted per-bucket series ≠ the rejected rollup.
- **E3 renorm re-baselines thresholds:** after E3 lands, the terminal aggregate shifts slightly as
  under-coverage → ~0; re-baseline AC-1c rather than treating small movement as regression.

## Phase gate

Phase 1 complete. **Phase 3 review complete and passed:** system-architect APPROVE-WITH-REVISIONS (folded
in), business-analyst APPROVE-WITH-REVISIONS (folded in), financial-auditor **APPROVE** on re-review (Method
B verified to reconcile at $2,677.30; the "cannot spike" overstatement corrected, real-swing framing +
AC-7b added). Plan is **Phase 2 Rev 2 — reviewer-approved**. Next: **STOP before Phase 4/5 pending USER
approval.** On approval: Phase 4 (docs incl. superseding ADR — needs explicit user OK), Phase 5 (Change 1
backend + Change 2 frontend), rebuild `--backend-only` + `--frontend-only`, Phase 6 auditor sign-off.
