# Effective-Cost Held-Reward-Income Fix — Implementation Plan

**Status:** Draft (Phase 2) · **Severity:** HIGH · **Class:** read-model + series numerator-lane defect
**Audit:** [`results/effective-cost-held-reward-income-audit.md`](../../results/effective-cost-held-reward-income-audit.md)
**Blast radius:** read-model only — **no** replay / renormalization / ledger / pricing-cache change. Deploy `--backend-only`.

---

## 1. Scope

- **Wallets / assets:** all; user-visible defect anchored on `FAMILY:SAVAX` (staked AVAX derivative), also `FAMILY:AVAX`, `FAMILY:ARB`, `FAMILY:BBSOL`, `FAMILY:MNT`, `FAMILY:ETH` (all reward/LST/airdrop/carry families).
- **Metric:** ADR-062 single break-even "Effective cost", `offsetLane = NET`, on the dashboard header AND the move-basis effective-cost graph line.
- **Out of scope:** ledger/replay/AVCO/normalization/pricing (all already correct — net-lane basis and net AVCO are right; only the metric consumes the wrong lane).

## 2. Root cause (from audit §1.4, §5)

The break-even **numerator is pinned to the Market lane** in both paths:
- Header: `BreakEvenCalculator` uses `FamilyBreakEvenInput.marketBasisUsd` ← `TokenPositionView.provableBasisUsd()` (= `avcoUsd × coveredQty`).
- Series: `AssetLedgerChartService.effectiveCostAfterUsd` uses `blended.marketAvco() × coveredQty`.

The only discount (`attributedOffset`) accumulates **realized** PnL/income. Zero-cost income **received-and-still-HELD** (reward claims, staking yield never sold) lowers the **Net** basis but generates no realized PnL, so it is invisible → effective cost ≈ Market AVCO. For sAVAX: $11.96 vs correct **$0.53** (Net AVCO) — 22.6× overstatement; $27.65 of held free income ignored.

**Fix (validated CORRECT, audit §2):** when `offsetLane == NET`, numerator = **Net-lane held basis**, reducing to `effectiveBasis = netBasis − netRealizedProfit` (floors unchanged). No double-count: every unit is HELD (net basis, reward=$0) XOR DISPOSED (netRealized) — never both.

## 3. Ordered changes (read-model only)

### C0 — Shared lane-pick helper (architect edit #3, do first)
Introduce one pure helper so the "NET ⇒ net numerator" rule lives in exactly one place for all consumers:
- `chooseLaneBasis(OffsetLane, marketBasis, netBasis)` and `chooseLaneAvco(OffsetLane, marketAvco, netAvco)` in a shared support class (next to `OffsetLane` / `BreakEvenAttributionService`). Used by `BreakEvenCalculator` (numerator + fold) and `AssetLedgerChartService` (series).

### C1 — Numerator across ALL FOUR `FamilyBreakEvenInput` producers (architect BLOCKER #1/#2)
`BreakEvenCalculator.compute` is shared by **three** input producers plus the series. All must move together, else the shared `heldBasis = chooseLaneBasis(...)` selection sees `zeroIfNull(null)=0` and collapses break-even to ≈$0 (provable regression: `AssetLedgerQueryServiceTest` reconciliation asserts at `:2154/:2224/:2331`).

| # | Producer | File · line | Feeds | Net-basis source to pass |
|---|---|---|---|---|
| A | `SessionDashboardQueryService.computeBreakEvenByFamily` | `:905` | dashboard header scalar | `TokenPositionView.netAvcoUsd × coveredQuantity` (new `netProvableBasisUsd()`) |
| **B** | `AssetLedgerQueryService.enrichWithBreakEven` | `:179` | **move-basis header scalar** (`view.current().breakEvenUsd()`) | `zeroIfNull(currentState.netTotalCostBasisUsd())` (field exists, `:508`) |
| C | `AssetLedgerQueryService.loadChildAttributionData` | `:255` | child rollup inputs (basis = ZERO) | `ZERO` (symmetry with existing `marketBasisUsd = ZERO`) |
| D | `AssetLedgerChartService.effectiveCostAfterUsd` | `:384` | move-basis series/graph line | `blended.netAvco()` (see C3) |

Steps:
1. Add `netBasisUsd` to `BreakEvenCalculator.FamilyBreakEvenInput` (positional — ~15+ test constructions must be updated, architect #6).
2. Populate `netBasisUsd` at producers A, B, C per the table. Add additive `TokenPositionView.netProvableBasisUsd()` (= `netAvcoUsd × coveredQuantity`); **do NOT touch `provableBasisUsd()`** — it also feeds `totalProvableBasisUsd`/unrealized-PnL% (`SessionDashboardQueryService:360-365`) which must stay Market lane.
3. In `BreakEvenCalculator.compute`, numerator `heldBasis = chooseLaneBasis(lane, marketBasisUsd, netBasisUsd)` (+ fold basis, C2). **Keep `attributedOffset` byte-identical** (already reconstructs `market + income = netRealized` under NET).
4. Derive `averageCostUsd` and `lockedSurplusUsd` from the **same** chosen-lane basis (E-2, E-5). **Architect #5:** `averageCostUsd` moving to net is a user-visible dashboard "Average cost" change — keep Market `avcoUsd` as the demoted diagnostic column.

### C2 — Fold as a lane-tagged basis pair (`BreakEvenCalculator.FoldAccumulator`) — E-4 (architect #4)
- Refactor `FoldAccumulator` to carry a **`(marketBasis, netBasis)` pair** (mirror the existing `LaneAmount (market, income)`), select via the C0 helper at numerator assembly — not a separate `if (lane==NET)` branch that can drift.
- **Config caveat:** `break-even-attribution.json` has **no** `foldHeldExposure:true` mappable entry today (GMX-GM/Pendle-PT carry raw-contract identity, no `CLUSTER:*`/`FAMILY:*` key). So C2 is **unit-test-only** — coverage via a synthetic/test-loaded attribution with `foldHeldExposure:true` on a mappable source→target asserting the parent numerator stays net-lane. (Pre-existing asymmetry: in path B children come from `loadChildAttributionData` with basis=ZERO, so the fold folds zero there for both lanes — harmless while folds off; flag, don't fix.)

### C3 — Series numerator (`AssetLedgerChartService.effectiveCostAfterUsd`) — §5
- Pass `offsetLane` into `effectiveCostAfterUsd` (already in enclosing scope, `:102`); numerator avco = `chooseLaneAvco(lane, blended.marketAvco(), blended.netAvco())` (`netAvco()` already materialized by `BlendedPoint`, `:367-369`).
- Repoint the two guards (the `== null` return and `dustGuardedBlendedAvco(...)`) at the chosen-lane avco.
- Leave RM-3 over-sliver suppression, offset weave (self floored / child unfloored), and terminal reconciliation unchanged. **No change to `BlendedExposureAvcoSeriesBuilder`** (already blends both lanes); the `blendedMarketAvco` diagnostic lane (`:183`) stays market.

### C4 — Keep as-is (explicitly unchanged)
AC-8 cluster carve-out (offset-only; moot post-ADR-083 but harmless), external realized-loss floor, ADR-082 offset implausibility guard, AC-7 rate-adjusted denominator, AC-9 fail-closed denominator, RM-3 sliver suppression, entire MARKET-lane path.

### C5 — User-facing copy (REQUIRED, per BA review — not optional)
- The move-basis **"Effective cost" hint currently states it "starts from the market cost basis"** — this becomes **false** after the fix. Reword the tooltip/hint to: effective cost starts from the **net (real-cash) basis** — rewards/airdrops/yield received and still held are credited as free, and realized profit is banked against it. Locate in the asset-ledger / move-basis header hint component and any dashboard "Effective cost" tooltip.
- Fix stale doc-comments in `BreakEvenCalculator` / `AssetLedgerChartService` that describe the numerator as Market-lane.
- **Dashboard avg-cost null-fallback:** audit the dashboard "Avg cost" column fallback (`averageCostUsd ?? avcoUsd`) so it does not silently re-leak the Market lane when break-even/avg-cost move to net; keep both on the same lane.
- Optional: surface `heldIncomeUsd = marketBasis − netBasis` in `BreakEvenResult` so the tooltip can quantify the free-income drop. Defer unless cheap.

## 4. Docs (before code, Phase 4)

- **ADR-062** — amendment (2026-07-24): break-even numerator uses the Net lane under `offsetLane=NET` (held zero-cost income credited, not only realized income); series mirrors header. Note the sAVAX exemplar ($11.96→$0.53) and the no-double-count invariant.
- **`docs/pipeline/cost-basis/`** — effective-cost section: numerator lane = net under NET; "rewards reduce effective cost even while held".
- **`docs/reference/`** (glossary / api / move-basis metric docs as needed) — align the "Effective cost" definition wording.

## 5. Acceptance criteria (Phase 6)

**Live-data (absolute, ± tolerance):**
- `FAMILY:SAVAX` header break-even → **$0.5304 ±1e-4**, effective basis **$1.2827**, qty 2.4184; move-basis line terminal reconciles to the same value.
- `FAMILY:ARB` → **≈ $0.0232/unit** (net $1.50 / 64.64 held); `FAMILY:AVAX` → ≈ net AVCO (**≈ $0.15/unit**, net $0.042 / 0.2724); `FAMILY:BBSOL` → **≈ $172.2/unit** (net $319.65 / 1.856, was ~$185.5); `FAMILY:ETH` → drops ~$52/unit (net $12,312.99 / 3.8226 ≈ **$3,221** vs market ≈ $3,273).
- Plain families (USDT, USDC, LP_RECEIPT, equities, BTC) break-even unchanged within fee-bps noise (net ≡ market).
- USDT (net realized loss −$163) break-even still ≤ market AVCO — external loss floor holds, no inflation.
- **`breakEven ≤ averageCost`** for every family after both are moved to the net lane (E-2); no family where effective cost reads above its own average cost except via the AC-8 cluster carve-out (now moot).

**Unit tests (`BreakEvenCalculatorTest` / `AssetLedgerChartServiceTest` / `SessionDashboardQueryServiceTest`):**
- **Mixed HELD+DISPOSED** reward family (the actual no-double-count proof): buy + reward-receive + partial sell → break-even = `(netBasis − netRealized)/heldQty`, no double credit.
- **Reward-only** family (100% held rewards, no buys, no sells) → break-even ≈ **$0** (NOT suppressed/hidden).
- **Past break-even** family (netBasis − netRealized < 0) → break-even floored to **$0** + positive `lockedSurplusUsd` (surplus derived from the net effective basis).
- **Dust / fail-closed denominator** unchanged (null covered → null metric; $1 blended dust guard intact).
- **MARKET-lane regression guard:** with `offsetLane=MARKET`, every family's break-even is byte-identical to pre-fix (numerator still market).
- **Header ↔ series parity:** `AssetLedgerQueryServiceTest` reconciliation asserts (`:2154/:2224/:2331` — `terminal.effectiveCostAfterUsd() == current().breakEvenUsd()`) extended to a net-lane held-income scenario (netBasis < marketBasis, zero realized) proving they match at the **lower** net value. **Re-baseline** existing NET/MARKET terminal tests (`:2165`, `:2219-2228`) whose scenarios already carry held zero-cost income.
- **Fold-child net-basis lane-consistency** (C2): synthetic `foldHeldExposure:true` attribution — activated fold folds net basis under NET, market under MARKET.
- **Positional-constructor fan-out** (architect #6): the new `netBasisUsd` field updates all ~15+ `FamilyBreakEvenInput(...)` constructions in `BreakEvenCalculatorTest`.
- Assert `TokenPositionView.provableBasisUsd()` / unrealized-PnL% stays **Market** lane (additive `netProvableBasisUsd()` only).

**Edge/scope verifications:**
- **Borrowed / liability-backed inflows** carry **net basis == market basis** (verified in ledger: MNT/USDC/GHO/USDE/DOGS/USDT/TON/USD₮0 BORROW ACQUIRE all net≡market) → NO spurious ~$0 effective cost. Add a regression assertion.
- Rebase tokens (stETH-style) explicitly out of scope (income recognition is unchanged; only numerator lane moves).

**Sign-off:** Auditor re-verification — no Medium+ effective-cost issues remain; sAVAX authoritative reconstruction reconciles with DB. QA: before/after screenshots for SAVAX, ARB, BBSOL, one riser (SOL/TON), and one plain family (USDT); confirm the **whole historical series** re-bases (not just terminal) and new "past $0" badges appear where expected.

## 6. Risks

- **Metric-definition shift is user-visible.** Reward/LST families drop (some materially: SAVAX −95.6%, ARB −89%, BBSOL −7.2%). Mitigation: this is the user's explicitly requested semantics ("rewards are free; effective cost must reflect them"); documented in ADR-062 amendment; average cost (market) still available for contrast.
- **SOL/TON/BTC rise <0.2%** (net basis is gas-inclusive). Correct per "net of gas/fees" but could surprise if shown beside a lower market average cost → resolved by moving `averageCostUsd` to the same lane (C1.4 / E-2).
- **Fold path inactive** — C2 is pre-emptive; verify no regression on families without folds; E-4 fold can only be unit-tested (no live fold-attribution config today).
- **Header/series divergence (architect BLOCKER):** the shared `BreakEvenCalculator.compute` has **4** producers — dashboard header (A), move-basis header scalar (B, `AssetLedgerQueryService.enrichWithBreakEven`), child rollup (C), series (D). All four must land together; missing B/C collapses the move-basis header to ≈$0 and breaks the `:2154/:2224/:2331` reconciliation tests.
- **Borrowed / liability-backed inflows (BA catch): VERIFIED SAFE.** Ledger shows BORROW/ACQUIRE legs carry `netBasis == marketBasis` (repayment obligation), so the net numerator leaves them unchanged — no spurious ~$0 effective cost. Regression assertion added (§5).
- **Increased exposure to upstream reward-misclassification.** The net numerator now trusts that "zero-net-basis income" is genuinely free. If a *real-cost* inflow were ever misclassified as reward (net $0), the fix would understate its effective cost. This is a pre-existing normalization concern surfaced (not caused) by the fix; no action here beyond noting it for future reward-classification audits.
- **Whole series re-bases**, not just the terminal point — historical move-basis line shifts for reward families; expected, capture in QA screenshots.
