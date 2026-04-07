# 90 — Run 19 Base Native Shortfall And Basis Provability Closeout

## Context

Run 19 proved that the clarification / bridge layer is no longer the main
blocker for authoritative ETH-family holdings:

1. the audited `zkSync -> Arbitrum / Across` pair is already correct
2. `asset_positions`, `on_chain_balances`, and `reconciled_holdings` are all
   materialized again
3. current ETH-family quantity is provable

The remaining blocker is now the replay/accounting interpretation of incomplete
native history, especially on:

- `wallet = 0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f`
- `network = BASE`
- `asset = ETH`

Confirmed run 19 evidence:

- `reconciled_holdings.currentQuantity = 0.001092538442123013`
- `asset_positions.quantity = 0`
- `asset_positions.totalCostBasisUsd = 0`
- `asset_positions.hasIncompleteHistory = true`
- `asset_positions.unresolvedFlagCount = 294`
- normalized native `BASE / ETH` net for this wallet across persisted canonical
  rows is `-1.032898112606630091`
- the Base explorer transaction history for this wallet currently starts at
  `2025-05-20T06:56:59Z`, so the remaining ambiguity is not “late rerun data”
  from after the replayed period

The problem is therefore no longer “wrong latest classification”.

It is that replay currently floors insufficient quantity to zero and only keeps
boolean unresolved flags. The system does **not** persist:

- how much historical quantity was missing when replay tried to spend or move
  more than it had
- how much of the current live quantity is still basis-covered vs uncovered

That makes user-facing AVCO ambiguous even when current quantity itself is
correct.

## Architect Decision

Keep the fix conservative and explicitly auditable:

1. do **not** invent synthetic cost basis for live-positive uncovered quantity
2. preserve replay quantity shortfall when outbound quantity exceeds replayed
   available quantity
3. materialize current-basis provability explicitly in `reconciled_holdings`
4. keep existing reconciliation status contract intact; use new fields to
   distinguish “live quantity exists but current basis is not fully provable”

## BA Scope / Acceptance Criteria

### DoD

1. Replay persists `quantityShortfall` on `asset_positions` whenever an
   outbound replay step tries to consume more quantity than the current replay
   bucket contains.
2. The shortfall does not silently disappear behind floor-to-zero quantity.
3. `reconciled_holdings` exposes:
   - `basisBackedDerivedQuantity`
   - `currentCoveredQuantity`
   - `currentUncoveredQuantity`
   - `currentCostBasisProvable`
   - `quantityShortfall`
4. `currentCoveredQuantity` is bounded by replay-carried quantity, not by live
   quantity alone.
5. Current live accrual beyond replay principal is surfaced as
   `currentUncoveredQuantity`, not silently treated as basis-backed principal.
6. Current live-positive rows with replay shortfall and zero carried basis are
   surfaced as uncovered current quantity, not as opaque generic `MISMATCH`
   only.

### In Scope

- replay shortfall persistence
- conservative current-quantity provability fields on `reconciled_holdings`
- regression tests
- rerun preparation

### Out Of Scope

- synthetic opening-balance inference
- widening Base backfill beyond current persisted horizon
- broader LP / swap semantic reclassification on Base
- `sessionId` lineage on derived collections

## Backend Tasks

1. `BE-90-01` Persist replay `quantityShortfall` on `asset_positions`
2. `BE-90-02` Avoid silent full-cost relief when outbound replay quantity
   exceeds available replay quantity
3. `BE-90-03` Materialize `basisBackedDerivedQuantity`,
   `currentCoveredQuantity`, `currentUncoveredQuantity`, and
   `currentCostBasisProvable` in `reconciled_holdings`
4. `BE-90-04` Add regression coverage for shortfall and accrual-driven uncovered
   current quantity
5. `BE-90-05` Prepare rerun

## Operational Follow-Up

After this slice lands:

1. rerun normalization, pricing, replay, balance refresh, reconciliation, and
   holdings materialization
2. re-audit:
   - `BASE / ETH` on the main wallet
   - authoritative ETH-family subtotal using `currentCoveredQuantity`
   - residual `MISMATCH` rows whose current quantity is still fully uncovered
