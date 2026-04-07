# 80 — Run 3 Accounting Asset Identity And Holdings Reconciliation

## Context

`results/stat/run/3` confirmed that the current replay still overstates holdings
and breaks `ETH-family` basis continuity.

Live evidence:

1. live on-chain `ETH-family` quantity is `3.088644671782175893`
2. replay-materialized `asset_positions` `ETH-family` quantity is
   `17.036084752922533655`
3. `Bybit ETH` raw transfer history is duplicated by shadow rows on both
   inbound and outbound sides
4. `Bybit` replay inventory is still split by transfer network
5. known native aliases still materialize into duplicate same-wallet
   same-network holdings buckets

This is no longer an isolated `ETH family` bug. The missing contract is a
general `accounting asset identity` layer for replay/materialized holdings.

## Architect Decision

Keep raw evidence and canonical normalized docs traceable exactly as they are.

Introduce a replay-only accounting identity contract with these rules:

1. `audit identity` and `accounting identity` are different concepts.
2. `Bybit` inventory is venue-scoped, not network-scoped.
3. known native aliases must collapse into one per-network accounting identity.
4. family carry remains allowlisted and policy-driven.
5. same-symbol different-token contracts stay separate unless a dedicated policy
   explicitly allowlists them.

This slice must fix the immediate `ETH-family holdings + basis reconciliation`
problem, but the design must remain generic for all supported assets.

## BA Scope / Acceptance Criteria

### DoD

1. Matched `Bybit` shadow transfer rows are excluded from active accounting for
   both:
   - exchange-side deposit shadow rows
   - exchange-side withdrawal shadow rows
2. `Bybit` transfer shadow matching tolerates fee-bearing gross/net deltas when
   the chain-aware sibling is still the only bounded best match.
3. Replay uses venue-scoped inventory identity for `Bybit`, so transfer network
   metadata no longer creates separate `asset_positions` buckets.
4. Replay uses canonical per-network native asset identity, so known native
   aliases no longer create duplicate holdings buckets.
5. Matched `Bybit ETH -> on-chain WETH` still carries basis and does not create
   realized sale on principal.
6. `WETH -> aManWETH` still preserves carried basis through custody deposit.
7. After rerun, `asset_positions` no longer show duplicated `Bybit ETH`
   holdings caused by:
   - deposit shadow rows
   - withdrawal shadow rows
   - `Bybit` network fragmentation
8. After rerun, `asset_positions` no longer show duplicated `ZKSYNC ETH`
   holdings caused by native alias fragmentation.

### In Scope

- replay/materialization identity
- `Bybit` transfer shadow suppression
- native asset alias normalization for holdings identity
- regression tests

### Out Of Scope

- generic same-symbol token collapsing
- staked ETH derivatives:
  - `stETH`
  - `wstETH`
  - `cbETH`
  - `mETH`
  - `cmETH`
  - `rETH`
- turning `asset_positions` into the final user-facing holdings API in this
  slice

## Edge Cases

- Case: `fund_asset_changes` deposit row duplicates a matched chain-aware
  `withdraw_deposit` sibling.
  Expected: shadow row is audit-visible but excluded from active replay.

- Case: `fund_asset_changes` withdrawal row has gross amount slightly larger
  than the matched chain-aware sibling because of withdrawal fee.
  Expected: shadow row is audit-visible but excluded from active replay when it
  remains the only bounded best sibling candidate.

- Case: `Bybit` historical inventory is acquired in venue scope and later moved
  out through `ARBITRUM`, `BASE`, `MANTLE`, or `ETHEREUM`.
  Expected: replay relieves one venue-scoped inventory lot, not one per-network
  lot.

- Case: native `ETH` on `ZKSYNC` appears once with alias
  `0x000000000000000000000000000000000000800a` and once as `SYMBOL:ETH`.
  Expected: replay materializes one holdings bucket.

- Case: same-symbol scam token named `ETH` or `USDC`.
  Expected: remains separate unless it is an explicitly allowlisted native alias
  or approved family-equivalent wrapper.

## Backend Tasks

1. `BE-80-01` Introduce accounting asset identity support
   - add one shared support class for replay/materialization identity
   - support venue-scoped `Bybit` inventory identity
   - support canonical native-asset identity for known network aliases
   - keep current family carry contract separate

2. `BE-80-02` Generalize `Bybit` shadow transfer suppression
   - extend shadow matching from withdrawals to deposits
   - keep raw rows unchanged
   - exclude only the shadow row from active accounting
   - widen bounded matcher tolerance for fee-bearing gross/net shadow pairs

3. `BE-80-03` Make replay asset keys use accounting identity
   - collapse `Bybit` inventory across transfer networks
   - collapse known native aliases into one per-network holdings key
   - keep unrelated same-symbol assets separate

4. `BE-80-04` Regression coverage
   - matched deposit shadow becomes excluded
   - fee-bearing withdrawal shadow becomes excluded
   - `Bybit` transfer from venue inventory to on-chain wrapper preserves basis
   - native alias duplication on `ZKSYNC ETH` materializes one holdings bucket
   - existing `ETH family` custody carry to `aManWETH` remains green

## Operational Follow-Up

After code lands:

1. clear derived state only
2. preserve raw evidence and historical prices
3. rerun `normalization -> clarification -> pricing -> accounting replay`
4. re-audit:
   - `ETH-family` live quantity vs `asset_positions`
   - `Bybit ETH` current holding should be zero
   - `aManWETH` must carry non-zero basis

## Follow-Up Backlog (Not In This Slice)

1. formalize reconciled holdings read-model separate from internal
   `asset_positions`
2. populate `onChainQuantity`, `onChainCapturedAt`, and
   `reconciliationStatus`
3. move from hand-maintained family lists to a registry-backed accounting
   identity catalog
