# ADR-019 — Corridor carry policy: outbound-AVCO preservation

**Status:** Accepted  
**Date:** 2026-05-29  
**Context:** ETH-C1 — Bybit→on-chain corridor `0xa5e755…` transferred 3.06 ETH at `$1,639/ETH` while outbound Bybit slice AVCO was `$1,714/ETH`, depressing all downstream AMANWETH cost basis.

## Problem

When a Bybit-to-on-chain corridor transfer fires (`BYBIT-CORRIDOR:MANTLE:*`):

1. `ContinuityCarryService` computes `carryBasis = totalBasisMoved` correctly.
2. But the **per-unit rate** written to the inbound WETH bucket uses a blended or stale computation, producing `avcoAfterUsd = $1,639` instead of the outbound slice rate `$1,714`.
3. All downstream receipt tokens (AMANWETH, lending receipts) inherit the depressed rate.

Evidence:
- Outbound Bybit umbrella ETH: `bb ≈ 3.164`, `avco ≈ 1,714`, `basisDelta = -5,016`.
- Inbound WETH on Mantle: `qty = 3.06`, `avcoAfterUsd = 1,639`, `basisDelta = +5,016`.
- Implied rate: `5,016 / 3.06 = $1,639` ≠ outbound slice `$1,714`.

## Decision

### Rule: Corridor `CARRY_IN` must use the outbound-slice per-unit AVCO

For any corridor transfer (`CARRY_OUT` / `CARRY_IN` pair with a Bybit venue source):

1. **Compute inbound AVCO as:** `carryBasis / movedQty`, where `carryBasis = movedQty × outboundSliceAvco`.
2. **Outbound slice AVCO** is the `avcoBeforeUsd` of the source venue bucket at the moment of the `CARRY_OUT` leg — **not** a post-move residual or pool average.
3. The residual source-bucket AVCO after `CARRY_OUT` is allowed to differ (it reflects remaining lot composition), but must not influence the inbound rate.
4. Inbound `avcoAfterUsd` = `outboundSliceAvco` ± rounding (within 0.1%).

### Implementation target

- `ContinuityCarryService` — add `outboundSliceAvco` parameter; use it for inbound rate when set.
- `ReplayTransferClassifier` / `BybitVenueInternalReplayHandler` — populate `outboundSliceAvco` from the `CARRY_OUT` leg's `avcoBeforeUsd` before dispatching `CARRY_IN`.
- `PassThroughCorridorPlan` — wire the rate through the plan if used.

### Dedup guard

After corridor carry, the 3.06 ETH is on-chain as WETH/AMANWETH. A `PortfolioAvcoAggregationService` must **not** also count the Bybit residual 0.1 ETH in the same bucket as the moved quantity. Dedup is managed in ETH-C2 / P1, not here.

## Consequences

- Corridor `0xa5e755…` inbound WETH `avcoAfterUsd` rises from `$1,639` to **≥ $1,714**.
- AMANWETH (Aave Mantle lending receipt) inherits the corrected basis.
- Full prod rebuild required after this fix.
- Regression: residual Bybit umbrella AVCO (`~$3,918`) must not spike further — it reflects remaining lot, not a bug.

## Acceptance

```javascript
// After rebuild:
db.asset_ledger_points.findOne({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  "txHash": /a5e755a68349/i,
  assetSymbol: "WETH",
  basisEffect: "CARRY_IN"
});
// expect: avcoAfterUsd >= 1714, basisDeltaUsd ~= 5240+
```

## Related

- `docs/tasks/avco-eth-2300-band-implementation-plan.md` — P0-C
- ETH-C1 diagnosis: `results/avco-eth-timeline-audit/blockers.md`
- ADR-017 — timeline AVCO authority (read model; not affected by this fix)
