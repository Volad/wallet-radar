# ADR-062 — Break-even (effective-cost) metric with configurable cross-family PnL attribution

- **Status:** Accepted (amended 2026-07-24 — cluster-carry, see ADR-083)
- **Date:** 2026-07-18
- **Theme:** Read-model / cost basis / dashboard + move-basis UI
- **Relates to:** ADR-040 (dual cost basis — Net/Market AVCO), ADR-054 (per-asset AVCO for staked derivatives), ADR-045 / ADR-061 (move-basis series), ADR-059 (config-plane pattern), ADR-083 (cluster-carry)

## Amendment (2026-07-24, per ADR-083) — no intra-cluster realized attribution

ADR-083 switches **intra-cluster** cross-canonical conversions (ETH↔mETH, AVAX↔sAVAX, SOL↔mSOL, …)
from realize-at-market to a both-lane basis carry with **realized PnL = 0**. Consequently these
conversions produce **no realized P&L** for the cross-family attribution to fold: the break-even
offset only ever receives realized P&L from genuine **exits to a non-cluster asset** (USDT/fiat/BTC)
and from cross-cluster moves. Same-cluster form changes no longer create the phantom cluster→family
"income" that ADR-082/FB-01 guarded against (that guard becomes largely inert on carried flows;
retained as defense-in-depth). `foldHeldExposure` (held-basis fold) is unaffected.

## Context

AVCO answers *"what is the average carried cost of the units I still hold?"*. It is deliberately **balance-only**: realized P&L that has already been taken out of an asset does not lower the cost of the units that remain.

Users think about a second, complementary question: *"at what price do I break even on this asset, given the profit I already realized on it (and on its economically-related assets)?"*. Concretely: the user sold **cmETH** for a **+$2.5k realized profit** and rotated into **ETH** via USDT. AVCO for ETH is correctly ~$3,029/ETH, but the user's *effective* cost is lower because the ETH purchase was partly funded by realized cmETH profit. cmETH and ETH are **separate accounting families** (ADR-054 keeps them separate for AVCO correctness — cmETH is C1→`FAMILY:METH`, a distinct pool from `FAMILY:ETH`), so this cross-asset relationship is invisible to AVCO.

We want a **break-even (effective-cost)** metric that:

1. Credits **realized P&L** back against the remaining basis to produce an effective per-unit cost.
2. Can attribute a child asset's realized P&L to a **parent family** (e.g. staked-ETH cluster → ETH) via configuration, so families stay separated for AVCO but roll up correctly for break-even.
3. Does **not** distort AVCO or any replay/ledger state — it is a pure read-model derivation.

### Offset lane — Market vs Net (amendment 2026-07-21: default is now **Net**)

**Original decision (2026-07-18):** use the **Market** lane as the offset. Rationale at the time: portfolio-wide **Net** realized P&L was ~$21k including ~$17k of "zero-basis income", but a later audit (`results/repay-transferout-phantom-income-audit.md`) proved ≈$6.7k+ of that was **phantom** — a borrow-basis leak (ADR-040 §5 non-compliance) that booked the market value of borrowed principal as Net income on REPAY / EXTERNAL_TRANSFER_OUT / SWAP.

**After the borrow-basis fix** (ADR-040 §5 compliance), Net-lane zero-basis income dropped to ≈$7.4k and is now **genuine** — staking/restaking yield (cmETH/PT-cmETH), airdrops, farm rewards, LP-fee claims — not phantom loan principal. With the phantom removed, the earlier objection ("crediting phantom income drives break-even below $0") no longer holds: real yield legitimately lowers a holder's effective cost.

**Amended decision:** the offset lane is a **configurable** top-level field `offsetLane` in `break-even-attribution.json`, with values `NET` or `MARKET`, **defaulting to `NET`**. Under `NET`, the offset is the Net-lane realized P&L (trading profit **plus** realized income), so receiving rewards/yield reduces effective cost — matching the user's mental model ("effective cost should fall as I earn yield"). Under `MARKET`, the offset is trading-profit only (the original behaviour). The loss floor (`max(offset, 0)`) and the `[0, AVCO]` bound are unchanged in both lanes. `incomeReceivedUsd` remains surfaced separately regardless of lane.

> **Note (ADR-082, 2026-07-24, FB-01).** `offsetLane=NET` credits the raw Net-lane realized P&L, so a
> NET-lane basis-recycling artifact upstream (ADR-040 "Bug B" / ADR-054 §2, firing on realizing
> distinct-canonical swaps) surfaced here as a fabricated +$1,894 FAMILY:METH "income" that inflated
> the ETH break-even offset (effective cost understated ~$500/ETH). **ADR-082 fixes this at the
> source** (NET-lane re-base on realize), after which this metric's `netRealizedPnlUsd` input is
> artifact-free and `offsetLane=NET` credits genuine reward income only — no ADR-062 semantics change.
> As defense-in-depth, `BreakEvenCalculator` additionally fails a target's cluster NET offset **closed
> to the Market lane** when credited income is implausibly large (a recycling-regression signature);
> this guard is a strict no-op on correct post-fix inputs and never touches standalone reward families.

## Decision

Introduce a **read-model-only** break-even calculator and a **configurable attribution plane**. No changes to the AVCO replay engine, RPC usage, or Mongo mutations.

### 1. Metric definitions (per accounting family `Y`)

All inputs come from existing read-model aggregations over `AssetLedgerPoint`:

- `marketBasisUsd(Y)` = current Market-lane cost basis of covered holdings (`totalCostBasisUsd`).
- `coveredQty(Y)` = basis-backed held quantity (existing `coveredQuantity`).
- `marketRealizedPnlUsd(src)` = Σ `realisedPnlDeltaUsd` for family `src` (Market lane; trading-only, income-neutral).
- `netRealizedPnlUsd(src)` = Σ `netRealisedPnlDeltaUsd` for family `src` (Net lane; includes income principal).

Attribution (see §2) partitions every family `src` to exactly one **target** family `attributionTarget(src)`. Then:

- `attributedRealizedPnlUsd(Y)` = Σ over `src` where `attributionTarget(src) == Y` of `marketRealizedPnlUsd(src)` (Market-lane trading profit; surfaced as-is).
- `attributedIncomeUsd(Y)` = Σ over the same `src` of `(netRealizedPnlUsd(src) − marketRealizedPnlUsd(src))` (realized zero-basis income).
- `attributedOffsetUsd(Y)` = `max(offsetPnl(Y), 0)` where `offsetPnl(Y) = attributedRealizedPnlUsd(Y) + attributedIncomeUsd(Y)` when `offsetLane == NET` (default), or `attributedRealizedPnlUsd(Y)` when `offsetLane == MARKET` — **only realized net profit discounts effective cost** (see "Loss floor" below). The move-basis effective-cost time series weaves the same lane's realized deltas chronologically.
- `effectiveBasisUsd(Y)` = `marketBasisUsd(Y) − attributedOffsetUsd(Y)`.
- `breakEvenUsd(Y)` = `coveredQty(Y) > 0 ? max(effectiveBasisUsd(Y), 0) / coveredQty(Y) : null`.
- `lockedSurplusUsd(Y)` = `effectiveBasisUsd(Y) < 0 ? −effectiveBasisUsd(Y) : 0` — realized profit that has already fully recovered the remaining basis ("you are past break-even by this much").
- `incomeReceivedUsd(Y)` = `Σ (netRealizedPnlUsd(src) − marketRealizedPnlUsd(src))` over the same attributed `src` — informational; the zero-basis income booked against `Y`'s cluster.

**Effective cost ∈ [0, AVCO].** The metric is surfaced in the UI as **"Effective cost"** (per remaining unit).

**Loss floor (why the offset is floored at 0).** The offset only ever *reduces* effective cost by realized profit; a realized net **loss** never *raises* it. Without this floor, dividing a fixed lifetime dollar loss by a small remaining quantity produces an absurd per-unit figure — e.g. USDT that has been mostly spent (covered ≈ 7 units) with a −$116 lifetime trading loss would show ≈ $17/unit. Flooring the offset keeps effective cost ≤ AVCO and intuitively bounded: *"your average cost, reduced by the trading profit you already banked, and never worse than your average cost."* Realized losses remain fully visible in realized P&L; they simply do not inflate the cost of units still held.

**Floor at $0 (upper credit).** When banked profit exceeds the remaining basis, effective cost floors at $0 and the excess is reported as `lockedSurplusUsd`.

### 2. Configurable attribution plane

A new classpath config `break-even-attribution.json` (ADR-059 loader→service style) maps a **source key** (a `CLUSTER:*` or `FAMILY:*` identity) to a **target `FAMILY:*`**. It is explicit and applies to **every family**, not just ETH — staking clusters for SOL and AVAX roll up the same way:

```json
{
  "version": 1,
  "offsetLane": "NET",
  "attributions": [
    { "source": "CLUSTER:ETH_STAKING",  "target": "FAMILY:ETH"  },
    { "source": "CLUSTER:SOL_STAKING",  "target": "FAMILY:SOL"  },
    { "source": "CLUSTER:AVAX_STAKING", "target": "FAMILY:AVAX" }
  ]
}
```

`offsetLane` is optional and defaults to `NET` when absent. It is global (applies to every family).

Cluster keys are defined by `AccountingAssetClassificationSupport.normalizationClusterForSymbol(...)`. A family with no matching cluster/family entry self-attributes, so effective cost degrades to `AVCO − bankedProfit/qty` for standalone assets.

**Resolution for a family `src` (with a representative asset symbol):**

1. If an explicit `FAMILY:*` → target entry exists for `src`, use it (explicit overrides cluster).
2. Else compute `cluster = normalizationClusterForSymbol(symbol)` (`AccountingAssetClassificationSupport`); if a `CLUSTER:* → target` entry exists **and** `target != src`, use `target`.
3. Else `src` maps to itself (self-attribution).

**Partition invariant (no double count):** each family resolves to exactly one target. A family that redirects to a parent does **not** also self-credit; its own break-even card shows the credit as *contributed to `<parent>`* and its own effective basis equals its market basis (no self-offset). The target receives its own trading P&L plus all redirected children.

### 3. Surfacing

Read-model fields added to existing DTOs (no new endpoints). The UI label is **"Effective cost"**.

- **Dashboard token card** (`SessionDashboardQueryService.TokenPositionView` → `SessionDashboardResponse.TokenPositionEntry` → frontend `TokenPosition`): `breakEvenUsd`, `lockedSurplusUsd`, `incomeReceivedUsd`, `attributionTargetFamily` (null when self). Rendered as its **own dashboard column**, not folded into the AVCO/avg-cost cell.
- **Move-basis header** (`AssetLedgerQueryService.CurrentStateView` → `SessionAssetLedgerResponse.CurrentState` → asset-ledger page): same fields, rendered as a fourth stat card next to Market / Balance / Blended AVCO with a `(?)` info hint. The hint lists the **real member assets** of the family actually present in the ledger (`familyMemberSymbols`), plus the definition and worked example.
- **Move-basis chart line** (`AssetLedgerChartService` timeline → `blendedNetAvcoAfterUsd`-style `effectiveCostAfterUsd` per timeline entry → asset-ledger chart): an **Effective-cost time series** for the viewed family, computed at each timeline point as `max(marketBasis(t) − max(cumulativeAttributedOffset(t), 0), 0) / coveredQty(t)`. `cumulativeAttributedOffset(t)` weaves the viewed family's own realized P&L together with its attributed cluster children's realized P&L chronologically, in the configured `offsetLane` (Net = trading + income deltas, the default; Market = trading deltas only). Toggleable like the other AVCO lines; enabled by default. Its terminal value reconciles with the header `breakEvenUsd`. This works for every family page (ETH, SOL, AVAX, …) via the same config. The series divides by the **blended** ETH-equivalent covered denominator (LP-parked / receipt-corridor ETH folded back in by `BlendedExposureAvcoSeriesBuilder`), the same basis the header uses, and applies a **fail-closed dust guard on that denominator** (AC-7 / AC-10 parity, ADR-062 Wave 3, 2026-07): when the blended covered ETH-equivalent basis is a dust residual (the same `$1` predicate as the blended-AVCO guard) the point renders UNAVAILABLE ("—") instead of the `offset ÷ dust-denominator` spike seen on LP-deployment windows. A healthy denominator whose numerator floors to 0 (banked locked surplus, R2) still renders `$0` — only the dust-denominator explosions are suppressed.

### Terminology

The metric is named **"Effective cost"** in the UI (not "break-even"), because with the loss floor it never exceeds AVCO — it represents the effective per-unit cost of the units you still hold after crediting banked trading profit.

## Consequences

- **Positive:** Users see a truthful effective-cost number; families remain separated for AVCO correctness (ADR-054 untouched); attribution is data-driven and extensible (AVAX/SOL staking clusters can opt in later); zero replay/RPC/schema risk.
- **Neutral:** Break-even is a *presentation* figure, not a tax basis. It intentionally differs from AVCO (balance-only) and from Net realized P&L (includes income).
- **Trade-off:** Because income is excluded from the offset, break-even ≥ AVCO − (trading profit / qty) and never reflects yield. Yield is shown separately as `incomeReceivedUsd`.
- **Guardrail:** The partition invariant must hold — a unit test asserts every family resolves to exactly one target and that Σ attributed market PnL across targets equals Σ market PnL across all families (conservation of the offset).

## Amendment 2026-07-23 (Wave 3) — intra-cluster loss carve-out, rate-adjusted denominator, single-metric presentation

Wave 3 of the ETH-family effective-cost plan refines the metric in three ways. **All shipped changes are read-model + config only** and do not alter ADR-054 ledger semantics.

> **AC-11 (D7 residual-dust) — reverted.** A replay-layer variant (`GenericFlowReplayEngine.closeResidualExitDust`) that zeroed the sub-ε exit residual's basis and marked it uncovered was implemented and then **reverted**: marking the residual `uncovered` had non-local side effects on the covered-first consumption logic, flipping later cmETH cross-canonical conversion disposals (cmETH→ETH convert, cmETH→PT swap) from realize-at-market to `PnL = 0`. That shifted FAMILY:METH realized PnL by ≈$1,840 and silently reverted the verified D3 fix — a Medium+ regression from a Low/cosmetic dust fix on a hot path. The residual-dust condition is therefore handled **display-side only** by the AC-10 blended-AVCO `$1` dust guard; a non-dust stranded residual basis (the ~$43 cmETH CARRY_IN artifact from the D2 earn-corridor conservation) is left in the ledger as immaterial (≈$11/ETH break-even impact) rather than corrected via risky replay surgery.

### AC-8 — intra-`CLUSTER:*_STAKING` loss-floor carve-out

The loss floor `max(offset, 0)` is retained **only for self/external realized amounts**. Realized amounts attributed **via a staking cluster** (`BreakEvenAttributionService.Attribution.viaStakingCluster()`, i.e. a `CLUSTER:*_STAKING` source drove the redirect) **bypass** the floor. Rationale: a staking-conversion loss inside a cluster (e.g. cmETH→ETH −$197.74) is an economic cost of holding the *same* underlying exposure, so it legitimately **raises** the ETH break-even above average cost. A standalone/external trading loss (e.g. USDT) still floors at AVCO — a realized loss on a *different* asset must never inflate the cost of units still held. The break-even numerator therefore uses `heldBasis_market − (clusterOffset_signed + max(externalOffset_signed, 0))`. `AssetLedgerChartService` applies the identical split (child cumulative offset unfloored, self cumulative offset floored, no re-floor of the combined offset) so the chart terminal reconciles to the header (asserted in tests).

### AC-7 — exchange-rate-adjusted (ETH-equivalent) denominator

The denominator is the **ETH-equivalent** covered quantity, not the raw token count. aTokens/WETH count 1:1 with the underlying family unit (they are C1 by ADR-054 construction, so a viewed family's own reconciled covered quantity already IS the ETH-equivalent). A staked derivative held as a fold child (AC-9) must supply its covered quantity **divided by its live staking rate** (mETH/cmETH/wstETH/weETH ≈ 1.06). This is **fail-closed**: `FamilyBreakEvenInput.coveredQuantity() == null` (rate unavailable) yields `breakEvenUsd = averageCostUsd = null` rather than silently assuming 1:1. The calculator consumes a pre-computed ETH-equivalent quantity from the caller; the caller sources the staking rate from the same place replay/pricing uses.

### AC-9 / D8 — held-exposure fold (mechanism shipped; GMX/Pendle disclosed-excluded)

An attribution entry may set `foldHeldExposure: true`. A flagged child then contributes its **held** market basis **and** ETH-equivalent covered quantity into the target's break-even basis and denominator (beyond the default P&L-only rollup). A fold child with a missing ETH-equivalent quantity fails the target metric closed. **GMX GM ETH/USD and Pendle PT-ETH are currently disclosed-excluded** from the ETH fold: they carry raw-contract `accountingFamilyIdentity` (e.g. `0x70d9…`) rather than a `CLUSTER:*`/`FAMILY:*` key, and their ETH-share (~$411 / +0.11 ETH-eq today) is a dynamic LP-composition value not present in the read-model. Activating their fold requires (a) an accounting family/cluster identity for these LP-receipt instruments and (b) a live ETH-share composition source — both outside read-model+config scope.

### Single-metric presentation (§5)

Exactly **two headline fields** per family:

- **`breakEvenUsd`** ("Break-even price") = `max(0, heldBasis_market − attributedRealizedProfit − bankedIncome) ÷ heldEthEquiv_rateAdjusted`. **R2: no average-cost cap** — with the AC-8 carve-out and uncovered fees/interest, break-even MAY exceed average cost.
- **`averageCostUsd`** ("Average cost") = `heldBasis_market ÷ heldEthEquiv_rateAdjusted`.

The numerator pins the **Market** lane for held basis; the offset (realized profit + banked income) is credited per the configured `offsetLane` (NET reconstructs `market + (net − market)` so Market-lane income is never double-counted). **[Superseded 2026-07-24 by the held-reward-income amendment below: under `offsetLane=NET` the numerator uses the Net lane, so income received-and-still-held is credited too.]** Balance AVCO and raw Blended AVCO are **demoted** into a nested `details` / diagnostic-lanes object (`SessionAssetLedgerResponse.CurrentState.details` → `DiagnosticLanes`), not deleted. `BreakEvenCalculator` remains the **sole** compute authority; the chart terminal reconciles to the header (tested). AC-10 applies the `$1` blended-AVCO dust guard so a dust residual renders "—" instead of a phantom figure.

## Amendment 2026-07-24 (RM-1 / RM-3) — fold in-flight CARRY corridors + fail-closed both-direction sliver guard

**Read-model + series only.** No change to classification, linking, pricing, AVCO, replay, or the ledger. Fixes the ETH-family effective-cost series dropping to a false `$0` while ETH is parked out of the liquid pool via a **CARRY** corridor (cross-wallet/cross-chain internal transfer, bridge-out, or `LENDING_LOOP_OPEN` collateral). Root cause: `BlendedExposureAvcoSeriesBuilder` re-folded only `REALLOCATE_*` corridors into the blended effective-cost denominator and excluded `CARRY_OUT/CARRY_IN`, so when ETH left the liquid pool via a CARRY the blended covered denominator collapsed to a sliver while the cumulative attributed offset stayed whole, flooring `max(marketBasis − offset, 0)` to `$0` until the matching `CARRY_IN`.

- **RM-1 (primary).** Same-family `CARRY_OUT` now parks its covered qty + market/net basis into the blended pool (like `REALLOCATE_OUT`), keyed by `correlationId` when present (LP `lp:*`, lending-loop `lending-loop:{openTxHash}`, bridge `bridge:*`) or by `lifecycleChainId` for a bare internal transfer with no correlation; the matching `CARRY_IN` (including `LENDING_LOOP_CLOSE`/`LENDING_LOOP_DECREASE`) closes it. This keeps the blended denominator whole through the in-flight leg. The relaxation applies to the **blended denominator only** — the AVCO/ledger lanes are untouched. The **C2 guard** (`isC2DistinctAsset`) still excludes wstETH/weETH/cmETH from the blended `FAMILY:ETH` pool whether they arrive as REALLOCATE or CARRY. The **B-ETH-06 terminal clamp** is kept: a CARRY corridor with no matching return and no open family-origin pool row (genuine bridge leak / dropped transfer) closes to zero, never lingers; a still-open lending-loop corridor keeps its basis-conserving residual (B-ETH-02).
- **RM-2 (consistency guard, no code change).** Aave aTokens (`AARBWETH/AMANWETH/ALINWETH/AZKSWETH/AWETH`) already carry `accountingFamilyIdentity = FAMILY:ETH` and are C1, so `includeInSpotFamilyTimelineAggregation` already counts their ETH-equivalent exposure in the spot lane / series denominator. No new config or aToken fold path; RM-1 restores CARRY-parked collateral so the series terminal reconciles with the scalar header break-even (`coveredRatio` 1.0).
- **RM-3.** `AssetLedgerChartService.isOverSliverArtifact` drops the `effectiveCost > blendedAvco × 1.1` AND-condition: any **sliver-denominated** point (`blendedCoveredQty < 5%` of the family's global/terminal peak) is now suppressed to `null` ("—") **regardless of direction** — the floor-to-`$0` side is blanked as well as the offset spike, since a floored `$0` on a sliver denominator is as misleading as the spike. A **healthy** (non-sliver) denominator is never suppressed here, so a genuine `$0` banked-locked-surplus floor (R2/W7) stays visible.

RM-4 (GMX GM ETH/USD + Pendle PT-ETH held-exposure fold) remains **disclosed-excluded** (see AC-9 / D8 above); RM-5 (bridge-leak matched-counterparty linking) is a linking-stage follow-up, not part of this read-model change.

## Amendment 2026-07-24 (held-reward-income) — Net-lane numerator under `offsetLane=NET`

**Read-model + series only.** No change to classification, linking, pricing, AVCO, replay, or the ledger. Plan: [`docs/tasks/effective-cost-held-reward-income-implementation-plan.md`](../tasks/effective-cost-held-reward-income-implementation-plan.md); audit: [`results/effective-cost-held-reward-income-audit.md`](../../results/effective-cost-held-reward-income-audit.md).

**Defect.** The §5 numerator pinned the **Market** lane for held basis and discounted only **realized** profit/income. Zero-cost income **received-and-still-HELD** (staking rewards, airdrops, LP-fee/lending-interest claims never sold) lowers the **Net** basis but produces no realized P&L, so it was invisible to the offset → effective cost ≈ Market AVCO. Exemplar: `FAMILY:SAVAX` showed **$11.96/sAVAX** while only ~$1.41 of real cash ever entered the AVAX/sAVAX cluster (rest are reward claims net=$0 + cluster-carry). Correct break-even = Net AVCO **$0.53/sAVAX** — a 22.6× overstatement. sAVAX is a staked derivative (ADR-083 `CLUSTER:AVAX_STAKING`).

**Fix.** Under `offsetLane=NET`, the break-even (and `averageCostUsd`) **numerator uses the Net-lane held basis**, so `effectiveBasis = netBasis − netRealizedProfit` (offset machinery, AC-8 cluster carve-out, external-loss floor, AC-7 denominator, AC-9 fail-closed, RM-3 sliver suppression, and the entire MARKET-lane path unchanged). **No double-count:** every unit is either HELD (net basis, reward = $0) or DISPOSED (in `netRealized`), never both.

- **Header + series parity.** The Net numerator is applied at **all four** consumers of the shared `BreakEvenCalculator.compute` / series: `SessionDashboardQueryService.computeBreakEvenByFamily` (dashboard scalar), `AssetLedgerQueryService.enrichWithBreakEven` (move-basis scalar) and `loadChildAttributionData` (child rollup) feeding `FamilyBreakEvenInput.netBasisUsd`, plus `AssetLedgerChartService.effectiveCostAfterUsd` using `blended.netAvco()`. A shared `chooseLaneBasis/chooseLaneAvco` helper keeps the lane decision in one place. The `:2154/:2224/:2331` reconciliation tests gate header↔series equality.
- **`averageCostUsd`** moves to the same (net) lane so `breakEven ≤ averageCost` stays intuitive; the Market `avcoUsd` (Balance AVCO) is retained as the demoted diagnostic.
- **Fold children (AC-9)** carry a lane-tagged `(marketBasis, netBasis)` pair so an activated GMX-GM/Pendle-PT fold cannot mix lanes (unit-test-only; no mappable `foldHeldExposure:true` entry exists today).
- **Cross-family impact.** Plain buys unchanged (net≡market: USDT/USDC/BTC/equities/LP-receipt). Reward/LST/airdrop/carry families correctly drop (SAVAX −95.6%, AVAX −98%, ARB −89%, BBSOL −7.2%, MNT −4%, ETH −1.6%). SOL/TON/BTC rise <0.2% because the net lane is gas-inclusive (consistent with "net of gas/fees"). **Borrowed/liability-backed inflows carry net≡market basis (repayment obligation), so they are NOT credited as free.**

## References

- `AccountingAssetClassificationSupport.normalizationClusterForSymbol(...)` — source of cluster keys.
- `SessionDashboardQueryService`, `AssetLedgerReconciliationService`, `AssetLedgerQueryService` — read-model integration points.
- `BreakEvenCalculator`, `BreakEvenAttributionService`, `BreakEvenAttributionLoader` — Wave 3 compute + attribution (`Attribution.viaStakingCluster()` / `foldHeldExposure()`).
- `backend/core/src/main/resources/break-even-attribution.json` — attribution config plane (`offsetLane`, per-entry `foldHeldExposure`).
- `AssetLedgerChartService.dustGuardedBlendedAvco(...)` — AC-10 read-model blended-AVCO `$1` dust guard, reused as the fail-closed guard on the effective-cost **series** denominator so the effective-cost line and the blended-AVCO line go UNAVAILABLE on the same dust point (the D7 mitigations shipped; the AC-11 replay variant was reverted, see the Wave 3 amendment).
- `BlendedExposureAvcoSeriesBuilder` (`isBlendedReallocation` / `parkKey` / `park` / `unpark` / `applyTerminalClamp`) — RM-1 (2026-07-24) same-family CARRY-corridor fold into the blended denominator (see the 2026-07-24 amendment); wired via `AssetLedgerChartService.buildTimelineProjection` over the family superset that already carries the CARRY points.
- `AssetLedgerChartService.isOverSliverArtifact(...)` — RM-3 (2026-07-24) fail-closed both-direction sliver-denominator suppression (floor-to-`$0` and spike).
