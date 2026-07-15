# ADR-031 — AVCO Null/Undefined Representation for Gas-Only and Reward Events

**Status:** Accepted (amended 2026-07-13 — extended to replay-time fail-closed for unpriceable C2 conversions, see ADR-054)  
**Date:** 2026-06-13  
**Workstream:** WS-4 (multi-counterparty bridge coverage / sponsored-gas AVCO)

---

## Context

`SPONSORED_GAS_IN` and `REWARD_CLAIM` events emitted after a position is fully drained
record `avcoAfterUsd = 0` in `asset_ledger_points`. This is replay-correct — there is no
basis-backed quantity to divide into. However, when the asset-ledger chart plots raw
`avcoAfterUsd`, these 0 values appear as V-dips that mislead the viewer into thinking the
cost basis dropped to zero from a real trade.

The same issue affects the move-basis timeline: `TimelineAvcoAuthority` already handles
this via the `isAllGasOnly` carry-forward path, but the raw `LedgerPointView` data served
to the asset-ledger chart uses `point.getAvcoAfterUsd()` directly.

---

## Decision

**In the asset-ledger read path** (`AssetLedgerQueryService.gasOnlyAvcoAfter`), represent
`avcoAfterUsd` as **null (undefined)** instead of 0 when ALL of the following conditions
hold:

1. `basisBackedQuantityAfter < 1e-8` — position has no meaningful basis backing
2. `normalizedType ∈ {SPONSORED_GAS_IN, REWARD_CLAIM}` OR `basisEffect = GAS_ONLY`

### Gate constraints

| Condition | Gate fires? |
|---|---|
| `SPONSORED_GAS_IN`, `basisBackedQty ≈ 0` | ✅ Yes — AVCO → null |
| `REWARD_CLAIM`, `basisBackedQty ≈ 0` | ✅ Yes — AVCO → null |
| Any event, `basisBackedQty > 1e-8` | ❌ No — real basis exists, report stored AVCO |
| `WRAP`, `basisBackedQty > 0` | ❌ No — WRAP always has real basis |
| `BRIDGE_OUT`, `basisBackedQty ≈ 0` | ❌ No — not a gas-only type |

---

## Consequences

- **Zero conservation impact:** `asset_ledger_points` are NOT written. `AvcoReplayService`
  is NOT modified. This is purely a read-path presentation filter.
- **Frontend contract:** `null` AVCO means "AVCO unavailable at this point"; the chart must
  not plot a 0 data point. This is the same semantics already used in the move-basis timeline
  (`KIND_UNAVAILABLE` → `avcoAfterUsd = null`).
- **Joint acceptance with WS-B corridor carry:** carry-through creates real basis, so
  `basisBackedQuantityAfter > 1e-8` at WS-B carry points → gate does not fire there.
- **Scope:** applies to the asset-ledger read path. The move-basis timeline (`TimelineAvcoAuthority`)
  already handles this via `isAllGasOnly` carry-forward (ADR-017); no change needed there.

---

## Amendment 2026-07-13 — replay-time fail-closed for unpriceable C2 conversions (ADR-054)

The original decision is a **read-path** presentation filter. ADR-054 ("one asset, one cost-basis pool")
extends the AVCO-undefined semantics to **replay time**: when a C2 (staked / value-accruing derivative)
conversion cannot resolve a market price for a leg, replay must **fail closed** — mark the affected
position/point `AVCO undefined` and flag it — rather than silently carrying the source basis 1:1 (which
would fabricate a price and defeat the per-asset model). This is a genuine replay-time state, distinct from
the read-path gate above; both surface as `null`/undefined AVCO to the frontend with the same "AVCO
unavailable" contract. A silent 1:1 carry on an unpriceable C2 leg is prohibited.

## Alternatives considered

- **Carry-forward in read path:** similar to `TimelineAvcoAuthority.isAllGasOnly`. Rejected
  because the asset-ledger chart aggregates raw points without a series accumulator; implementing
  carry-forward would require serializing state across the full point list and was deemed out of
  scope for this representation fix.
- **Backfill AVCO in replay:** rejected — would require re-running replay for all gas-only events,
  touching `asset_ledger_points` writes, and creating conservation risk.
