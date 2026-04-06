# 91 — Run 20 Asset Ledger Timeline And Debug Surface Closeout

## Context

Run 20 improved current-basis provability, but the accounting surface is still
too snapshot-heavy:

1. `asset_positions` only shows terminal replay state
2. `reconciled_holdings` only shows current live quantity plus terminal basis
3. when AVCO / cost basis looks wrong, operators still need to reconstruct the
   first broken step manually from raw inputs and final snapshots
4. the UI still has no direct asset-history read surface for AVCO/cost-basis
   evolution inside one session

The missing layer is an immutable replay trace that records what accounting
actually did after every basis-relevant step.

## Architect Decision

Introduce `AssetLedgerPoint` as the immutable replay truth for asset history and
debug.

Do not remove `asset_positions` or `reconciled_holdings` immediately.

Instead:

1. replay writes immutable `asset_ledger_points`
2. `asset_positions` becomes a compatibility snapshot projection from the latest
   exact-bucket ledger state
3. `reconciled_holdings` remains a compatibility current-holdings projection
   that joins current on-chain balances with replayed basis fields
4. UI asset-detail history reads use `asset_ledger_points`
5. cross-wallet session AVCO remains computed on request, never stored

## Naming

Approved runtime name:

- `AssetLedgerPoint`

Rejected alternatives:

- `PositionTimelinePoint`
  too vague, sounds like a mutable chart point rather than immutable accounting trace
- `ReconciledTimelinePoint`
  wrong layer; the timeline must exist before current-holdings reconciliation

## Domain Contract

One `AssetLedgerPoint` equals one applied replay state transition on one exact
asset bucket:

- scope key:
  - `walletAddress`
  - `networkId`
  - `accountingAssetIdentity`
- continuity/debug key:
  - `accountingFamilyIdentity`
- deterministic order:
  - `blockTimestamp`
  - `transactionIndex`
  - `normalizedTransactionId`
  - `flowIndex`
  - `replaySequence`

## Lifecycle Model

### Stored grouping fields

- `normalizedType`
- `lifecycleKind`
- `lifecycleStage`
- `basisEffect`
- `lifecycleChainId`

### Lifecycle kinds

- `SPOT`
- `TRANSFER`
- `BRIDGE`
- `CUSTODY`
- `LENDING`
- `STAKING`
- `VAULT`
- `LP`
- `ORDER`
- `LOOP`
- `WRAP`
- `REWARD`
- `DERIVATIVE`
- `MANUAL`
- `UNKNOWN`

### Lifecycle stages

- `SINGLE`
- `REQUEST`
- `SETTLEMENT`
- `SOURCE`
- `DESTINATION`

### Basis effects

- `ACQUIRE`
- `DISPOSE`
- `CARRY_OUT`
- `CARRY_IN`
- `REALLOCATE_OUT`
- `REALLOCATE_IN`
- `GAS_ONLY`
- `UNKNOWN`

## Family Policy

### Explicit cross-asset continuity families

- `FAMILY:ETH`
  - `ETH`
  - `WETH`
  - audited custody / wrapper / receipt assets already mapped into ETH-family
- `FAMILY:USDC`
  - `USDC`
  - audited stable wrappers already mapped into USDC-family continuity

### Default policy

If no broader family mapping exists:

- `accountingFamilyIdentity = accountingAssetIdentity`

This guarantees:

- UI history can aggregate by family
- replay can still debug exact-bucket state
- no synthetic cross-asset continuity is invented for unsupported families

## UI Read Contract

When the user opens an asset inside one session, WalletRadar must be able to
return:

1. aggregated session-level AVCO / cost-basis timeline for one family
2. lightweight event overlay markers
3. raw immutable ledger points for debug

The read path must be datastore-only:

- no RPC
- no explorer calls
- no replay-at-request-time

## BA Scope / Acceptance Criteria

### DoD

1. Replay persists immutable `asset_ledger_points`.
2. Every persisted point contains:
   - exact asset identity
   - family identity
   - lifecycle grouping
   - basis effect
   - deterministic order fields
   - quantity / cost basis / AVCO before and after
   - unresolved / shortfall diagnostics after the step
3. Session-level asset history can be read by filtering points to the current
   session wallet set and one `accountingFamilyIdentity`.
4. Session-level timeline is aggregated on read from wallet-level points; it is
   not pre-stored as cross-wallet AVCO state.
5. UI overlay markers are returned from the same immutable trace order.
6. The debug payload makes it obvious at which exact point replay first became:
   - incomplete
   - unresolved
   - shortfall-positive
7. `asset_positions` and `reconciled_holdings` remain available, but are
   documented and treated as compatibility projections rather than immutable
   truth.
8. Existing replay families continue to work:
   - spot buy/sell
   - external/internal transfer continuity
   - bridge continuity
   - protocol custody
   - lending deposit/withdraw
   - staking deposit/withdraw request/settlement
   - vault deposit/withdraw
   - LP entry/exit request/settlement/terminal
   - async spot order request/settlement
   - Euler loop families
   - wrap/unwrap
   - reward claim marker churn
9. Late carry attachment is visible as an explicit ledger point on the
   destination bucket instead of being hidden inside terminal state only.
10. Rerun preparation resets old derived collections including
    `asset_ledger_points`.

### In Scope

- immutable replay-trace document and repository
- replay emission of ledger points
- session asset-ledger query service
- REST endpoint for session family timeline
- docs, tests, rerun preparation

### Out Of Scope

- frontend rendering
- pagination / compression for very large asset histories
- historical on-chain balance timeline
- removing `asset_positions` / `reconciled_holdings` physically in this slice

## Backend Tasks

1. `BE-91-01` Add `AssetLedgerPoint` document, repository, indexes, enums, and
   family/lifecycle support helpers.
2. `BE-91-02` Extend `AvcoReplayService` to emit immutable ledger points for all
   replayed families and special continuity branches.
3. `BE-91-03` Persist explicit late-carry / settlement / reallocation points so
   hidden replay state is visible in the trace.
4. `BE-91-04` Keep `asset_positions` as a compatibility snapshot projection from
   replay state.
5. `BE-91-05` Keep `reconciled_holdings` as a compatibility current-holdings
   projection after on-chain reconciliation.
6. `BE-91-06` Add session asset-ledger query service:
   - load session wallets
   - load family-filtered raw points
   - aggregate session-level AVCO / cost-basis timeline on read
   - produce event overlay markers
7. `BE-91-07` Add REST endpoint for session asset-ledger history.
8. `BE-91-08` Update API docs and compatibility notes.
9. `BE-91-09` Add regression coverage for:
   - simple buy/sell timeline
   - bridge carry out / carry in
   - late carry attach
   - LP reallocation
   - session-level aggregation
10. `BE-91-10` Prepare rerun.

## Operational Follow-Up

After this slice lands:

1. rerun normalization, pricing, replay, on-chain refresh, reconciliation, and
   holdings materialization
2. validate that:
   - `asset_ledger_points > 0`
   - session `FAMILY:ETH` history is readable
   - the first suspicious point for any remaining mismatch is directly visible
     from the ledger trace
