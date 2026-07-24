# Effective-cost offset lane → configurable, default NET — Implementation Plan

- **Status:** Approved (user-requested; ADR-062 amended 2026-07-21)
- **Scope:** read-model only (no AVCO/replay/RPC/Mongo mutation). Verify with `--backend-only` (no renormalization).
- **Governing:** ADR-062 (2026-07-21 amendment — offset lane configurable, default NET).

## Goal
Effective cost must fall when the user earns realized income (rewards/yield), not only trading profit. Make the break-even offset lane configurable (`NET` | `MARKET`) via `break-even-attribution.json`, default **NET**. Safe now that the borrow-basis phantom income was removed (Net income $17k → $7.4k, genuine).

## Root of current behaviour
- `BreakEvenCalculator.compute`: `attributedOffset = attributedMarketPnl.max(0)` — Market lane only; `attributedIncome` is computed but only surfaced as `incomeReceivedUsd`, never subtracted.
- `AssetLedgerChartService.buildTimelineProjection`: weaves `cumulativeSelfMarketPnl` (point `realisedPnlDeltaUsd`) + `cumulativeChildMarketPnl` (`AttributedRealizedPnlEvent.marketRealisedPnlDeltaUsd`) — Market lane only.

## Ordered changes
1. **Config** `backend/core/src/main/resources/break-even-attribution.json`: add top-level `"offsetLane": "NET"`.
2. **Loader** `BreakEvenAttributionLoader`: parse optional `offsetLane` (`NET`|`MARKET`, case-insensitive; default `NET` when absent; reject other values). Add `offsetLane` to `LoadedBreakEvenAttribution`.
3. **Service** `BreakEvenAttributionService`: expose `OffsetLane offsetLane()` (enum `NET`/`MARKET`).
4. **Calculator** `BreakEvenCalculator.compute`: compute `offsetPnl = (lane == NET) ? attributed + income : attributed`; `attributedOffset = offsetPnl.max(0)`. Keep loss floor and `[0, AVCO]`. Keep `attributedRealizedPnlUsd` (market) and `incomeReceivedUsd` (income) as separate result fields (unchanged) so the UI still shows both components.
5. **Chart** `AssetLedgerChartService`:
   - Extend `AttributedRealizedPnlEvent` with `netRealisedPnlDeltaUsd` (in addition to `marketRealisedPnlDeltaUsd`).
   - Track `cumulativeSelfNetPnl` from the point's net realized delta (accumulator's net realized delta; if the accumulator lacks it, add it from `AssetLedgerPoint.netRealisedPnlDeltaUsd`) and `cumulativeChildNetPnl` from the child event's net delta.
   - Choose lane once from `BreakEvenAttributionService.offsetLane()`: offset series = NET(self+child) when NET, else MARKET(self+child). Feed that cumulative offset into `effectiveCostAfterUsd(...)`.
   - Terminal reconciliation: series terminal effective cost must equal the header `breakEvenUsd` under the same lane.
6. **Query wiring** `AssetLedgerQueryService`: populate `AttributedRealizedPnlEvent.netRealisedPnlDeltaUsd` from child points' `netRealisedPnlDeltaUsd`. No DTO field changes required (effective cost already surfaced).

## Tests
- `BreakEvenAttributionLoaderTest`: default NET when absent; parse NET/MARKET; reject invalid.
- `BreakEvenCalculatorTest`: add NET-lane cases — income reduces effective cost; income can drive `lockedSurplus` (>0) / breakEven=0; MARKET-lane case still matches old behaviour; loss floor holds in both lanes (USDT-like: net loss does not inflate). Update the existing partition/conservation test for the lane.
- `AssetLedgerChartService` test: terminal effective cost under NET equals header breakEven; NET series ≤ MARKET series (income ≥ 0).
- Regression: `attributedRealizedPnlUsd` and `incomeReceivedUsd` still reported separately.

## Docs
- ADR-062 amended (done). Update `docs/frontend/move-basis.md` effective-cost/hint wording: effective cost now reflects realized **income + trading profit** by default.

## Acceptance
- `--backend-only` rebuild; effective cost on dashboard + move-basis header/chart drops for families with realized income (e.g. ETH cluster incl. cmETH yield; sAVAX→AVAX reward income now visible where before it was ~$0 market-only).
- Terminal chart effective cost == header breakEven per family.
- Full `/financial-audit` confirms numbers and no double-count; effective cost ∈ [0, AVCO].

## Risks
- Effective cost will drop noticeably (income now credited) — intended, not a regression.
- Ensure conservation/partition invariant still holds with net offset (each family → one target; Σ per target).
