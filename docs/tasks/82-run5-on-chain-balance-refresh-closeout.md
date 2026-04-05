# 82 — Run 5 On-Chain Balance Refresh Closeout

## Context

Run 4 added the reconciliation consumer contract, but not the runtime producer.

Live evidence still shows:

1. `on_chain_balances` remains empty after replay
2. `asset_positions` still cannot be proven against chain balances
3. `ETH-family` current holdings remain audit-derived, not runtime-proven

This slice closes the missing runtime producer for `on_chain_balances`.

## Architect Decision

Use one bounded post-replay refresh pass, not an always-on polling system.

The refresh must:

1. stay inside the modular monolith
2. prefer indexed/account-style balance sources before direct RPC
3. derive the candidate asset universe from canonical normalized evidence, not
   from explorer pages or manual audit lists
4. remain generic for supported EVM assets already inside accounting scope
5. keep `Bybit` outside the on-chain balance-evidence lane

The runtime order becomes:

```text
pricing gate green
-> replay confirmed rows
-> refresh on_chain_balances
-> reconcile asset_positions
```

## BA Scope / Acceptance Criteria

### DoD

1. `CostBasisReplayJob` refreshes `on_chain_balances` before
   `AssetPositionReconciliationService`.
2. The refresh candidate universe is built from:
   - current `tracked_wallets`
   - `normalized_transactions.status = CONFIRMED`
   - `normalized_transactions.source = ON_CHAIN`
   - non-excluded accounting rows
3. Supported `Ankr Advanced API` networks use `ankr_getAccountBalance`
   as the primary balance source.
4. Supported explorer-indexed networks use explorer account balance
   endpoints before falling back to direct RPC.
   - `Etherscan`-compatible networks use account balance endpoints.
   - `Blockscout` networks use `api/v2/addresses/*` balance endpoints.
5. Direct RPC remains the last fallback only.
6. Quantities are normalized to persisted decimal quantity, not stored as raw
   integer hex.
7. Zero live balances remain persisted as real evidence.
8. `Bybit` inventory never produces `on_chain_balances`.
9. Contractless non-native symbol rows do not create synthetic balance
   evidence.
10. Docs explicitly state that `asset_positions` remains internal replay state
   and depends on live `on_chain_balances` refresh for proof.

### Edge Cases

- Case: native alias history on `ZKSYNC`
  Expected: one native balance evidence row, not duplicate alias rows.

- Case: token currently has zero quantity on-chain
  Expected: persisted `quantity = 0`, not missing evidence.

- Case: contractless non-native symbol-only flow
  Expected: skipped from refresh; reconciliation remains `NOT_APPLICABLE`.

- Case: replay is skipped because positions already exist
  Expected: refresh still runs before reconciliation.

- Case: `Mantle` current balances
  Expected: explorer-first path (`Mantlescan`/Etherscan-compatible) is used
  before direct RPC.

### In Scope

- bounded candidate query
- EVM balance refresh
- deterministic persistence to `on_chain_balances`
- replay integration
- regression tests
- documentation updates

### Out Of Scope

- public holdings endpoint
- discrepancy read model for on-chain-only balances
- Solana balance refresh

## Backend Tasks

1. `BE-82-01` Add bounded candidate query service
2. `BE-82-02` Add EVM on-chain balance refresh service
3. `BE-82-03` Persist deterministic latest balance evidence
4. `BE-82-04` Integrate refresh into replay orchestration
5. `BE-82-05` Add regression coverage and update docs

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve raw evidence and historical prices
3. rerun the pipeline
4. re-audit:
   - `on_chain_balances.count > 0`
   - `ETH-family` live quantity vs reconciled replay quantity
   - stale `Bybit ETH` current holding
   - non-zero carried basis on `MANTLE aManWETH`
