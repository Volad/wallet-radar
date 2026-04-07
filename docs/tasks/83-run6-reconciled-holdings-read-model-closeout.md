# 83 — Run 6 Reconciled Holdings Read Model Closeout

## Context

Run 5 closed the runtime producer for `on_chain_balances`, but one operator and
product gap remains:

1. `asset_positions` is still easy to misread as the current holdings table
2. current holdings quantity lives in `on_chain_balances`, while basis lives in
   `asset_positions`
3. there is no bounded runtime read model that combines those two contracts
   into one user-safe holdings source

Latest audit evidence confirms the distinction matters:

1. live `ETH-family` quantity is about `3.06807`
2. replay-materialized `asset_positions` still overstates current holdings
3. the main holding now sits on `Mantle Aave` as `aManWETH`
4. operator questions are now about "what is the current holding and its basis",
   not about replay quantity alone

This slice closes that gap by introducing one reconciled holdings read model.

## Architect Decision

Do not turn `asset_positions` into the user-facing holdings table.

Keep the existing separation:

- `asset_positions`
  internal replay state and basis source
- `on_chain_balances`
  current live quantity evidence

Add:

- `reconciled_holdings`
  bounded read model keyed by the same accounting identity

Rules:

1. current quantity must always come from `on_chain_balances`
2. basis fields must come from `asset_positions`
3. rows may persist with zero live quantity for operator/audit visibility
4. user-facing current holdings must filter to `currentHolding = true`
5. `BYBIT` inventory never materializes into `reconciled_holdings`
6. missing replay basis must not block live-balance visibility; it materializes
   as a holdings row with null basis fields

## BA Scope / Acceptance Criteria

### DoD

1. After `asset_positions` reconciliation, WalletRadar materializes
   `reconciled_holdings`.
2. Matching uses the same accounting identity layer as replay and
   reconciliation.
3. `currentQuantity` is copied from `on_chain_balances.quantity`.
4. Basis fields are copied from `asset_positions` when a matching replay row
   exists.
5. `currentHolding = true` only when `currentQuantity > 0`.
6. Zero live balances may still persist as reconciled rows for audit/operator
   use.
7. `BYBIT` inventory never produces reconciled holdings rows.
8. Docs explicitly state:
   - `asset_positions` is internal replay state
   - `reconciled_holdings` is the current holdings read model
   - user-facing holdings must read from `reconciled_holdings`

### Edge Cases

- Case: live on-chain evidence exists, replay row exists, quantities match.
  Expected: reconciled row has basis fields and `reconciliationStatus = MATCH`.

- Case: live on-chain evidence exists, replay row exists, quantities differ.
  Expected: reconciled row has basis fields and `reconciliationStatus = MISMATCH`.

- Case: live on-chain evidence exists, replay row is missing.
  Expected: reconciled row persists with null basis fields and
  `reconciliationStatus = NOT_APPLICABLE`.

- Case: live on-chain quantity is zero.
  Expected: row persists with `currentHolding = false`.

- Case: replay row is `BYBIT`.
  Expected: no reconciled holdings row.

### In Scope

- `reconciled_holdings` document/repository
- post-reconciliation materialization service
- replay-job integration
- regression tests
- documentation updates

### Out Of Scope

- public holdings endpoint
- portfolio totals endpoint
- unmatched live-balance discrepancy API
- Solana holdings read model

## Backend Tasks

1. `BE-83-01` Add `ReconciledHolding` domain contract
2. `BE-83-02` Implement post-reconciliation holdings materialization service
3. `BE-83-03` Integrate materialization into `CostBasisReplayJob`
4. `BE-83-04` Add regression coverage
5. `BE-83-05` Update docs and operator guidance

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve raw evidence and historical prices
3. rerun the pipeline
4. re-audit:
   - `reconciled_holdings` count
   - active `ETH-family` holdings from `currentHolding = true`
   - `Mantle aManWETH` live quantity + carried basis
   - stale `BYBIT ETH` absent from current holdings read model
