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

### Rule 1 — Corridor `CARRY_IN` must use the proportional outbound cost basis

For any corridor transfer (`CARRY_OUT` / `CARRY_IN` pair with a `BYBIT-CORRIDOR:` correlation):

1. **Compute corridor carry basis as:** `preDrainTotalBasis × (movedQty / preDrainTotalQty)`, capped at `preDrainTotalBasis`.
2. **Do NOT use** `movedQty × perWalletAvco` — `perWalletAvco` divides by covered-quantity only and is inflated when the position has `uncoveredQuantity > 0` (e.g., after an LP_EXIT with pool shortfall). This would inject phantom basis into the corridor destination.
3. **Outbound slice AVCO** (for the `avcoAfterUsd` of the inbound ledger point) is `carryBasis / movedQty` — the per-unit rate implied by the proportional formula.
4. The residual source-bucket AVCO after `CARRY_OUT` may differ and must not influence the inbound rate.

**Amendment note** (2026-05-30, B-3 fix): Original Rule 1 stated `carryBasis = movedQty × outboundSliceAvco` where `outboundSliceAvco = position.perWalletAvco()`. This was incorrect when `uncoveredQuantity > 0`; the formula above replaces it.

### Rule 2 — On-chain corridor ordering: CARRY_OUT before CARRY_IN

For on-chain `BYBIT-CORRIDOR:` `INTERNAL_TRANSFER` corridors involving two wallets on the same network (source wallet → destination wallet sharing the same `txHash`):

- The replay must sequence the source-wallet `CARRY_OUT` before the destination-wallet `CARRY_IN`.
- This is enforced by the `corridorContinuityFlowSign` tiebreaker in `ConfirmedReplayQueryService`, which applies to both Bybit-source and on-chain-source corridor transactions.
- Rationale: if the inbound is processed first, the family AVCO temporarily double-counts the moved quantity at zero basis, creating spurious chart spikes.

### Implementation targets

- `TransferReplayHandler` — replace P0-C block with proportional `preDrainTotalBasis × (movedQty / preDrainTotalQty)` formula; capture `preDrainTotalBasis` and `preDrainTotalQty` after `carrySource` is assigned, before `removeTransferCarry()`.
- `ConfirmedReplayQueryService` — rename `bybitContinuityFlowSign` → `corridorContinuityFlowSign`; extend to on-chain `BYBIT-CORRIDOR:` INTERNAL_TRANSFER.

### Dedup guard

After corridor carry, the 3.06 ETH is on-chain as WETH/AMANWETH. A `PortfolioAvcoAggregationService` must **not** also count the Bybit residual 0.1 ETH in the same bucket as the moved quantity. Dedup is managed in ETH-C2 / P1, not here.

## Consequences

- Corridor `0xa5e755…` inbound WETH `avcoAfterUsd` rises from `$1,639` to **≥ $1,714**.
- AMANWETH (Aave Mantle lending receipt) inherits the corrected basis.
- Full prod rebuild required after this fix.
- Regression: residual Bybit umbrella AVCO (`~$3,918`) must not spike further — it reflects remaining lot, not a bug.

## Acceptance

```javascript
// Original ETH-C1 check:
db.asset_ledger_points.findOne({
  accountingUniverseId: "df5e69cc-a0c0-4910-8b7d-74488fa266e2",
  "txHash": /a5e755a68349/i,
  assetSymbol: "WETH",
  basisEffect: "CARRY_IN"
});
// expect: avcoAfterUsd >= 1714, basisDeltaUsd ~= 5240+

// B-3 fix check (Sep 10 Mantle→Bybit cmETH):
db.asset_ledger_points.findOne({
  normalizedTransactionId: /BYBIT-33625378:FUNDING_HISTORY:f9cfb4eb/,
  basisEffect: "CARRY_IN"
});
// expect: costBasisDeltaUsd ~= 1898.79 (was 2517.25)

// B-3 fix check (Across bridge ETH):
db.asset_ledger_points.findOne({replaySequence: 619, basisEffect: "CARRY_IN"});
// expect: costBasisDeltaUsd ~= 7.68 (was 11.06)
```

## Related

- `docs/tasks/avco-eth-2300-band-implementation-plan.md` — P0-C
- ETH-C1 diagnosis: `results/avco-eth-timeline-audit/blockers.md`
- ADR-017 — timeline AVCO authority (read model; not affected by this fix)
