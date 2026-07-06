# ADR-045 — Family covered-weighted move-basis AVCO series (supersedes ADR-017 chart-source decision)

**Status:** Accepted
**Date:** 2026-07-02
**Supersedes:** ADR-017 §Decision ("Family rollup numerators are never chart AVCO sources") and its
per-`accountingAssetIdentity` `TimelineAvcoAuthority` selection for the primary move-basis chart line.
**Related:** ADR-031 (AVCO undefined representation), ADR-040 (dual Net/Tax AVCO), ADR-004
(physical quantity vs basis-backed).
**Context:** Replay #11 — the ETH move-basis chart showed sharp AVCO "needles" on dust events (e.g.
`$1,675.68 → $2,724.45` on a `GAS_ONLY`/`BRIDGE_OUT` with `|qtyΔ|<0.001`). A financial audit
(`results/blockers.md`, `results/required-changes.md` RC-E1) proved the terminal FAMILY:ETH covered-weighted
AVCO is stable and correct at **$2,677.30 (Tax) ≈ $2,676.99 (Net)**, basis $10,492.80 — the needle is a
read-model artifact, not a basis defect.

## Problem

`TimelineAvcoAuthority` (ADR-017) selects one **per-`accountingAssetIdentity`** ledger point per grouped
event as the chart `avcoAfterUsd`/`netAvcoAfterUsd`, applying read-time heuristics (median-outlier
rejection >10× median, `capCarriedAvco`, per-identity carry-forward). The frontend then chains a **global**
`before` against this **per-identity** `after`. On dust events the resolver switches which sub-pool it
reports, so the plotted line hops between sub-pools (proven: $1,675.68 = BASE-WETH lane, $2,724.45 =
Bybit/Arbitrum-ETH lane). The +63 % jump is the line changing lanes, not the basis changing.

ADR-017 rejected a "family rollup" as the chart source, but the rollup it rejected was
`Σ costBasisDelta / Σ raw qty` over **heterogeneous** members (LP-receipt share counts, cross-asset), which
was economically meaningless. That rejection does **not** apply to the estimator adopted here.

Two candidate replacement estimators were evaluated against Mongo (universe `df5e69cc…`, Replay #11):

| Estimator | Definition | Terminal Tax | Basis | Intermediate |
|---|---|---|---|---|
| **A — cumulative delta sum** (`AggregatedState.avco()`) | `Σ costBasisDelta / coveredQty` | $2,909.69 ❌ | $11,403.63 | 245 jumps >5 %, up to +332 % (non-conserving) |
| **B — per-bucket snapshot** | `Σ coveredᵢ·avcoᵢ / Σ coveredᵢ` over **live per-bucket states** | **$2,677.30** ✅ | $10,492.80 | 142 jumps >5 %, peak ~+101 % — all economically real |

Method A's per-event delta stream is not basis-conserving (disposals + corridor CARRY/REALLOCATE
half-cycles leave ~$911 residue) and manufactures a *new, larger* family of needles. Method B is a
per-bucket-state snapshot that reconciles exactly to the authoritative reconstruction.

## Decision

The **primary move-basis chart AVCO line** (both Net and Tax lanes, ADR-040) is the **Method B family
covered-weighted per-bucket-snapshot series**:

> at each grouped timeline event, `avcoAfterUsd = Σ coveredᵢ·avcoᵢ / Σ coveredᵢ` and
> `netAvcoAfterUsd = Σ coveredᵢ·netAvcoᵢ / Σ coveredᵢ`, taken over the **live per-bucket states** of the
> spot-family-filtered members after the event.

Rules:

1. **Per-bucket-state accumulator.** Key buckets by the existing `BucketKey`
   `(walletAddress, networkId, accountingAssetIdentity)`. Within a multi-point event take the **last**
   point per bucket. Read the stored `avcoAfterUsd`/`netAvcoAfterUsd` and `basisBackedQuantityAfter`;
   `coveredᵢ = min(basisBackedQuantityAfter, qtyAfter)`. Order by
   `(blockTimestamp, transactionIndex, replaySequence)`.
2. **Spot-family filter unchanged** — reuse `AccountingAssetFamilySupport.includeInSpotFamilyTimelineAggregation`
   (LP-RECEIPT and non-ETH excluded; staked-ETH symbols included per ADR-017's amended table).
3. **Undefined (ADR-031).** When `Σ coveredᵢ ≤ 0`, emit `avcoAfterUsd = null` — the line **breaks** (never
   plotted as $0). Re-acquisition starts a new segment.
4. **`avcoKind` ∈ {`PRIMARY_FLOW`, `UNAVAILABLE`}** only. **Never** emit `FAMILY_ROLLUP` on the chart line
   (the frontend y-axis projection filters that kind out).
5. **Backend owns before/after.** `TimelineEntryView` carries `avcoBeforeUsd`/`netAvcoBeforeUsd` = the
   previous event's Method-B `after`, so the series is a single continuous line by construction and the
   frontend does not synthesize `before` or carry values forward across a `null`.
6. **No heuristics on the primary line.** Method B produces no *false* excursions (no lane-hop, no
   non-conservation artifact), so median-outlier rejection and `capCarriedAvco` are removed from the
   primary line. `TimelineAvcoAuthority` is retired from the chart path; **no secondary per-identity
   series** is introduced (per-pool detail remains in the expandable event rows / tooltip, labelled
   distinct from the family line).

## Consequences

- The chart reconciles to the authoritative terminal ($2,677.30 Tax / $2,676.99 Net) and no longer draws
  false lane-hop needles on dust events; the estimator is deterministic and heuristic-free.
- **The line is NOT flat.** It still shows large, **economically-real** covered-weighted swings (auditor
  verified: 142 jumps >5 %, peak ~+101 %; exemplar `2025-09-10 LP_EXIT +0.862 ETH → $1,849→$3,715`).
  Root cause is this ADR's inherited ADR-017 exclusion of **LP-RECEIPT / locked ETH** from the spot-family
  view: the in-scope covered quantity oscillates (~0.1–1.0 ETH) as the bulk of ETH moves in/out of LP /
  bridge / receipt positions, so the covered-weighted average legitimately re-weights. These swings are the
  honest metric and must **not** be capped or flagged as regressions.
- A genuinely smooth "total ETH cost-basis" line would require **including** LP-RECEIPT/locked basis in the
  family series — out of scope here; tracked as an open product/architecture question alongside RC-E3
  (LP-exit/native basis reattribution).
- Rebuild: read-model only — `--backend-only` + `--frontend-only`, no replay reset.
- Dashboard header AVCO (live-balance + per-bucket ledger) is unchanged; the per-event raw `LedgerPointView`
  AVCO surface is unchanged.

## Related

- Plan: `docs/tasks/eth-move-basis-avco-chart-lane-consistency-implementation-plan.md`
- Audit: `results/blockers.md` (Replay #11), `results/required-changes.md` (RC-E1/E2/E3),
  `results/accounting-failure-analysis.md`
- Code: `AssetLedgerQueryService` (timeline loop), `TimelineAvcoAuthority` (retired from chart path),
  `asset-ledger-page.component.ts` (`reconcileMarkerAvcoSeries`, `buildYProjection`)
