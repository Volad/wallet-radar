# 81 — Run 4 Asset Position Reconciliation Contract

## Context

`results/stat/run/4` confirmed that replay output is still being misread as a
user-facing holdings table.

Latest evidence:

1. live on-chain `ETH-family` quantity is `3.088705856605636123`
2. on-chain-wallet `asset_positions` materialize `8.628990703471765155`
3. `asset_positions` still materialize stale `Bybit ETH`
4. `asset_positions.onChainQuantity` and `reconciliationStatus` remain empty /
   `NOT_APPLICABLE`
5. docs already define reconciliation against `on_chain_balances`, but runtime
   does not execute that contract

This slice does not replace replay.

It adds the missing post-replay reconciliation layer so holdings proof becomes
generic for all supported assets.

## Architect Decision

Keep:

- raw evidence immutable
- canonical normalized docs traceable
- `asset_positions` as internal replay state

Add:

- one post-replay reconciliation pass
- one reusable `OnChainBalance` repository contract
- one shared accounting-identity matcher reused by replay and reconciliation

Rules:

1. `asset_positions` is not the user-facing holdings table.
2. Reconciliation compares replay state to `on_chain_balances`.
3. Matching uses accounting identity, not raw storage identity.
4. `Bybit` positions are `NOT_APPLICABLE` for on-chain reconciliation.
5. Missing balance evidence must not be silently treated as zero.

## BA Scope / Acceptance Criteria

### DoD

1. After replay, `asset_positions` rows with matching `on_chain_balances`
   evidence get:
   - `onChainQuantity`
   - `onChainCapturedAt`
   - `reconciliationStatus`
2. Reconciliation matching uses the same accounting identity layer as replay,
   including:
   - venue-scoped `Bybit` handling
   - native alias collapsing
3. `Bybit` positions are never assigned synthetic chain balances and remain
   `NOT_APPLICABLE`.
4. Missing balance evidence does not become synthetic zero balance. It remains
   `NOT_APPLICABLE`.
5. Matching balance evidence with equal quantity marks `MATCH`.
6. Matching balance evidence with different quantity marks `MISMATCH`.
7. `ZKSYNC ETH` native alias balance and replay bucket reconcile through one
   accounting identity.
8. Docs explicitly state that `asset_positions` is internal replay state and
   user-facing holdings require reconciled balance evidence.

### In Scope

- `OnChainBalance` domain/repository
- post-replay reconciliation service
- replay-job integration
- regression tests
- documentation updates

### Out Of Scope

- current-balance polling architecture
- user-facing holdings API
- public portfolio endpoints
- generic same-symbol token collapsing
- redefinition of accounting families

## Edge Cases

- Case: on-chain wallet has balance evidence for a native alias contract and the
  replay bucket uses canonical native identity.
  Expected: one reconciled match.

- Case: `Bybit` venue position exists in replay.
  Expected: `reconciliationStatus = NOT_APPLICABLE`.

- Case: replay row exists but no balance evidence exists.
  Expected: `NOT_APPLICABLE`, not synthetic zero.

- Case: balance evidence exists and quantity differs.
  Expected: `MISMATCH`.

## Backend Tasks

1. `BE-81-01` Add `OnChainBalance` domain contract
   - create document model and repository for `on_chain_balances`
   - keep schema minimal and replay-facing

2. `BE-81-02` Generalize accounting identity for reconciliation
   - reuse accounting identity logic outside replay flows
   - support native alias reconciliation
   - support venue-scoped `Bybit` exclusion

3. `BE-81-03` Implement post-replay reconciliation
   - read `asset_positions`
   - read `on_chain_balances`
   - populate `onChainQuantity`, `onChainCapturedAt`, `reconciliationStatus`
   - keep missing evidence as `NOT_APPLICABLE`

4. `BE-81-04` Integrate reconciliation into replay job
   - run after successful AVCO replay
   - also run when replay is skipped but `asset_positions` already exist and
     the gate is green

5. `BE-81-05` Regression coverage
   - native alias `MATCH`
   - quantity mismatch -> `MISMATCH`
   - missing balance -> `NOT_APPLICABLE`
   - `Bybit` -> `NOT_APPLICABLE`

## Operational Follow-Up

After code lands:

1. clear derived state only
2. preserve raw evidence and historical prices
3. rerun `normalization -> clarification -> pricing -> accounting replay`
4. re-audit:
   - `ETH-family` live quantity vs `asset_positions`
   - `Bybit ETH current holding should be zero`
   - populated reconciliation fields

## Follow-Up Backlog (Not In This Slice)

1. separate `reconciled_holdings` collection / read model
2. balance refresh producer for `on_chain_balances`
3. user-facing holdings and portfolio APIs sourced from reconciled state only
