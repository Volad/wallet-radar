# 108 — Owner-Aware Accounting Universe and Derived Lineage Closeout

## Context

The latest audit and follow-up discussion exposed a material architectural gap:

1. canonical normalization is owner-agnostic by design
2. replay continuity should be owner-aware
3. current replay output was still effectively installation-global

Concretely:

- `AccountingUniverseService` had become a transient resolver derived from the
  current session payload
- `asset_ledger_points` had no stable owner lineage
- `on_chain_balances` had no session lineage
- `CostBasisReplayJob` replayed every confirmed canonical row globally
- stale replay-heal logic could be satisfied by unrelated derived rows

This created a real basis-risk:

- installation-wide tracked-wallet heuristics could still classify a transfer as
  continuity-capable
- replay could then attach carry using rows outside the intended owner scope
- current-balance refresh and replay bootstrap checks could be healed by
  unrelated data from another session

The risk was not hypothetical anymore. The ETH investigation showed that
owner-aware lineage had to be restored before deeper bridge / Aave carry fixes
could be trusted.

## Decision

Reintroduce persisted additive accounting universes as the stable owner scope,
and bind derived collections to explicit lineage:

- `UserSession.accountingUniverseId`
- `accounting_universes`
- `asset_ledger_points.accountingUniverseId`
- `on_chain_balances.sessionId`

Runtime split:

- canonical normalization remains owner-agnostic
- replay/history uses `accountingUniverseId`
- current on-chain holdings uses the current session wallet subset and
  `sessionId`

This preserves the existing canonical contract while preventing derived basis
state from leaking across unrelated sessions.

## Runtime Contract

### Replay / History

- replay input is `normalized_transactions WHERE walletAddress IN universe.members`
- replay output is written only into `asset_ledger_points WHERE accountingUniverseId = ...`
- session family-history reads use `accountingUniverseId`
- replay bootstrap and replay completion checks must use `accountingUniverseId`

### Live Current Balances

- `on_chain_balances` is session-scoped current evidence only
- refresh deletes and rewrites balances only for that `sessionId`
- dashboard/current holding reads must use `sessionId` plus the current visible
  session wallets

### Classification / Continuity Boundary

- installation-wide `tracked_wallets` may still help canonical discovery
- owner-aware carry attachment happens later, at replay time
- if a canonical row is continuity-capable globally but its counterparty is not
  inside the active accounting universe, replay must degrade conservatively
  rather than inherit basis from another owner scope

## Backend Tasks

1. `BE-108-01` Persist accounting universe linkage on `UserSession`.
2. `BE-108-02` Add `AccountingUniverseSyncService`:
   - create additive universe on session save/update
   - synchronize current on-chain wallets
   - synchronize enabled exchange account refs
   - never auto-remove historical members
3. `BE-108-03` Switch `AccountingUniverseService` back to persisted-universe
   first, with bounded fallback for legacy sessions.
4. `BE-108-04` Add `accountingUniverseId` to `asset_ledger_points` and make
   replay persistence universe-scoped.
5. `BE-108-05` Add `sessionId` to `on_chain_balances` and make balance refresh
   session-scoped.
6. `BE-108-06` Scope replay input, gate counts, and stat validation to the
   active accounting universe member refs.
7. `BE-108-07` Update asset-ledger/dashboard reads to:
   - use `accountingUniverseId` for history / replay state
   - use `sessionId` for current balances
8. `BE-108-08` Update stale resume / replay bootstrap checks so unrelated
   derived rows cannot satisfy completion for another session.
9. `BE-108-09` Prepare replay-only rerun:
   - clear derived `asset_ledger_points`
   - clear derived `on_chain_balances`
   - preserve immutable raw and canonical normalized evidence

## Acceptance Criteria

1. `asset_ledger_points` rows produced for one session are tagged with that
   session's `accountingUniverseId`.
2. `on_chain_balances` rows produced for one session are tagged with that
   `sessionId`.
3. `GET /sessions/{sessionId}/asset-ledger` reads history from that session's
   accounting universe, including connected integrations and historical additive
   members.
4. `GET /sessions/{sessionId}/dashboard` reads current balances only from that
   session's live wallet subset.
5. Replay bootstrap / stale-heal logic no longer succeeds because unrelated
   `asset_ledger_points` or `on_chain_balances` exist elsewhere in the
   installation.
6. Canonical normalization behavior is unchanged: the slice does not move
   provider calls or alter canonical transaction types.

## Trade-Offs

### What This Fixes Now

- owner-aware derived lineage
- replay/history isolation
- current-balance isolation
- safer basis continuity for multi-session installs

### What This Slice Does Not Fully Solve

- `normalized_transactions` still stores replay-time sale annotations on flows
  (`avcoAtTimeOfSale`, `realisedPnlUsd`)
- that surface remains effectively global per canonical row
- full universe-specific realized-PnL annotations would require a later split
  of replay-derived transaction overlays from canonical normalized documents

This residual limitation is acceptable for the current slice because the most
critical continuity / basis leakage risk lives in derived replay state and
current-balance evidence, not in the immutable canonical stream itself.

## Verification

- `compileJava`
- targeted replay / dashboard / asset-ledger tests
- replay-only Mongo rerun without deleting raw evidence
- live session verification after Docker restart
