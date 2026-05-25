# ADR-004 — Physical quantity vs basis-backed quantity (cycle/2 E1)

## Status

Accepted.

## Context

Cycle/2 authoritative balance proof (`cycle-autorun/cycle-data/cycle/2/results/authoritative_balance_proof.md`)
shows a class of accounting-engine defects where withdrawals that exceed basis-backed
inventory are **clamped** in replay. The engine records the missing portion as
`quantityShortfall*`, but portfolio quantity surfaces use `quantityAfter`, which
represents a **basis-backed** slice. This causes “phantom inventory” to remain
on the custodian wallet (e.g. TON, BBSOL) and inflates market value.

The engine already tracks:

- `quantityAfter` (basis-backed running quantity)
- `quantityShortfallAfter`
- `uncoveredQuantityAfter`
- `basisBackedQuantityAfter`

but the read model does not expose a **physical** (inventory) quantity that is
conserved under raw deltas.

## Decision

1. Treat `quantityAfter` as **basis-backed** quantity, not physical inventory.
2. Define **physical quantity** for read surfaces as:

\[
physicalQuantityAfter = quantityAfter - quantityShortfallAfter
\]

This is the minimal conservative physical scalar that prevents clamp leakage
from inflating portfolio quantity while keeping realised-PnL math unchanged.

3. Portfolio quantity surfaces (dashboard token positions, Bybit integration
positions) must read `physicalQuantityAfter` when sourcing “current quantity”
from `asset_ledger_points`.

## Consequences

- No change to realised PnL computation logic.
- Read-side changes may hide phantom inventory that was previously displayed.
- Follow-up (optional): add explicit persisted fields `physicalQuantityBefore/After`
to `asset_ledger_points` if we want queryable projections without recomputation.

## References

- Cycle/2 proof: `cycle-autorun/cycle-data/cycle/2/results/authoritative_balance_proof.md`

