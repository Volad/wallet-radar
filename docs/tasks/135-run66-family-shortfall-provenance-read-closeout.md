# Run 66 Closeout: Family Shortfall Provenance Read Surface

## Problem

After the previous normalization and replay fixes, the remaining
`ETH uncovered ~= 0.305` no longer pointed to one active normalization bug.

The dominant live bucket:

- `MANTLE / AMANWETH`
- latest tx `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`

was only a downstream carrier of older unresolved coverage debt. Operators
could still see `current.uncoveredBuckets`, but the API did not expose the
historical transactions that first introduced that debt.

This made later audits expensive and created pressure to "fix coverage" in
replay even when the remaining uncovered quantity was an honest incomplete
history condition.

## Decision

Do not change normalization or AVCO semantics for this slice.

Expose a read-only family-level `shortfallSources` surface on the asset-ledger
endpoint instead.

This surface must:

- aggregate positive `quantityShortfallDelta` rows inside the selected family
- return the historical transactions that introduced the largest coverage gaps
- remain clearly diagnostic only

This slice does **not**:

- synthesize basis
- change replay ordering
- reclassify canonical rows
- require Mongo replay reset or on-chain renormalization

## Implementation

### Backend

- Extended `AssetLedgerQueryService` current-family read model with
  `shortfallSources`.
- Aggregated shortfall provenance by:
  - `walletAddress`
  - `networkId`
  - `txHash`
  - `normalizedType`
  - `protocolName`
- Sorted by descending accumulated `quantityShortfall`.
- Added DTO/controller mapping in the asset-ledger API response.

### Tests

- Updated `AssetLedgerQueryServiceTest` to assert empty `shortfallSources` in
  unaffected scenarios.
- Added a dedicated regression test proving that a historical positive
  `quantityShortfallDelta` source is surfaced alongside the current uncovered
  family state.

## Acceptance Criteria

- `GET /api/v1/sessions/{sessionId}/asset-ledger?familyIdentity=FAMILY:*`
  returns `current.shortfallSources`.
- The returned rows are derived from positive family-level
  `quantityShortfallDelta` history, not from current balance heuristics.
- The new surface does not mutate canonical rows, replay outputs, or current
  coverage calculations.
- API documentation states clearly that `shortfallSources` is diagnostic and
  not an exact one-to-one lineage proof for every live uncovered bucket.

## Risks

- Operators may over-interpret `shortfallSources` as an exact causal graph.
  Documentation and API wording must keep the scope narrow.
- CEX-native provenance rows may have `null` `txHash` or `networkId`; clients
  must tolerate that shape.
- Sorting by historical shortfall size may show sources that were later fully
  spent. That is acceptable for this slice because the intent is audit
  visibility, not deterministic replay lineage.

## Verification

Required:

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.costbasis.application.AssetLedgerQueryServiceTest'`
- backend rebuild / restart
- live API verification that `current.shortfallSources` appears for
  `FAMILY:ETH`
