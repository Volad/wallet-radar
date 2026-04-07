# 93 — Run 23 Current Uncovered Carry And Session Heal Closeout

## Context

Run 23 proved that quantity-side holdings were almost fully correct, but two
runtime problems remained:

1. `quantityShortfallAfter` was being used as if it were the current uncovered
   holding quantity
2. stale `ACCOUNTING_REPLAY / RUNNING` healing still depended on global derived
   collections rather than the current session wallet set

Observed failure mode:

- `BASE / ETH / main wallet` had live-positive current quantity but
  `currentCoveredQuantity = 0`
- the bucket had historical covered re-acquisitions after older shortfall, but
  those acquisitions were still treated as non-provable because lifetime
  shortfall poisoned the whole bucket
- `ACCOUNTING_REPLAY / RUNNING` could remain stale even when the current
  session outputs already existed, and unrelated wallet rows could satisfy the
  old global checks

## Decision

Finalize the current provability model:

1. keep `quantityShortfall*` as a lifetime audit metric
2. introduce explicit current `uncoveredQuantity*` state in replay
3. move uncovered quantity with continuity carry into the destination bucket
4. compute `basisBackedQuantityAfter` from current uncovered quantity, not from
   lifetime shortfall
5. expose covered/uncovered state in the asset-ledger query API
6. make replay bootstrap and stale-heal checks wallet-scoped to the current
   session

## Runtime Contract

Replay state now distinguishes:

- `quantityShortfall`
  - lifetime audit evidence that a historical step required more quantity than
    the current replay bucket contained
- `uncoveredQuantity`
  - current held quantity that still lacks provable carried basis

Invariants:

- `basisBackedQuantityAfter = max(quantityAfter - uncoveredQuantityAfter, 0)`
- later covered acquisitions must increase `basisBackedQuantityAfter` even if
  historical `quantityShortfallAfter > 0`
- uncovered continuity quantity must move to the destination bucket when the
  economic asset is still held
- session stale-heal may complete replay only when the current session wallet
  set already has:
  - `asset_ledger_points`
  - `on_chain_balances`

## Backend Tasks

1. `BE-93-01` Add `uncoveredQuantityDelta` and `uncoveredQuantityAfter` to
   `AssetLedgerPoint`.
2. `BE-93-02` Change replay state to track:
   - lifetime `quantityShortfall`
   - current `uncoveredQuantity`
3. `BE-93-03` Change continuity carry and bucket logic so covered and uncovered
   quantity move separately.
4. `BE-93-04` Recompute AVCO from covered quantity only.
5. `BE-93-05` Expose covered/uncovered current state through the
   session asset-ledger query endpoint.
6. `BE-93-06` Scope replay bootstrap and stale-heal checks by current session
   wallet addresses.
7. `BE-93-07` Add regression tests for:
   - historical shortfall followed by later covered acquisition
   - partial carry where destination keeps uncovered quantity
   - scheduler ignoring unrelated derived rows

## Acceptance Criteria

1. A bucket that once had lifetime shortfall can still become basis-covered
   again after later priced acquisition.
2. Partial continuity carry produces:
   - covered quantity on destination
   - uncovered tail on destination
   - lifetime shortfall on source
3. `basisBackedQuantityAfter` no longer collapses to zero solely because of old
   lifetime shortfall when current held quantity is newly covered.
4. `GET /api/v1/sessions/{sessionId}/asset-ledger` returns:
   - `current.coveredQuantity`
   - `current.uncoveredQuantity`
   - `timeline[*].coveredQuantityAfter`
   - `timeline[*].uncoveredQuantityAfter`
   - raw `ledgerPoints[*].uncoveredQuantityDelta`
   - raw `ledgerPoints[*].uncoveredQuantityAfter`
5. Replay bootstrap for one session is not satisfied by
   `asset_ledger_points` from unrelated wallets.
6. Stale `ACCOUNTING_REPLAY / RUNNING` is not healed by unrelated
   `asset_ledger_points` / `on_chain_balances`.
