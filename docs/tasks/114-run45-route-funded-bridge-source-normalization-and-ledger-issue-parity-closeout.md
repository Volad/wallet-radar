# 114 — Run 45 Route-Funded Bridge Source Normalization And Ledger Issue Parity Closeout

Status: Done

## Goal

Close the remaining audited `USDC` coverage gap caused by routed bridge source
rows that still normalize tx-level native funding as a second principal
`TRANSFER`, and align `asset-ledger` uncovered diagnostics with the already
accepted dashboard issue policy.

This slice intentionally prefers canonical normalization repair over another
replay-only workaround.

## Confirmed Diagnosis

Latest audit showed:

- `ETH` coverage is already effectively closed (`99.62%`)
- the largest live remaining basis gap moved to `USDC`
- the most important `USDC` miss sits on a routed bridge source tx:
  - source `0xf8cbeaf0d7d5f0a6a8a1e5e36d4d6f0bc1f2d154f0c6cb4a9e08b1dc1a2c6f2c`
  - destination `0x7483c0fb94c87b3adef2cc9c713fde1262e4ab70ec2db01445ab56981c7879a6`

Confirmed source raw facts:

- function: `swapAndStartBridgeTokensViaSquid(...)`
- outbound token principal: `USDT -21.81403`
- tx `value`: `0.084340262615309958 MNT`
- gas fee is separate

Current canonical problem:

- source row survives with:
  - one token `TRANSFER`
  - one native `TRANSFER`
  - one native `FEE`
- replay asset-changing settlement lane then rejects it because the source row
  no longer has one principal transfer leg
- destination `BRIDGE_IN` remains uncovered even though route correlation is
  already deterministic

This is a canonical flow-shape bug, not a remaining bridge replay bug.

Second confirmed issue:

- `asset-ledger` current `uncoveredBuckets[*].uncoveredReason` still used
  legacy values like `PARTIAL_CARRY`
- dashboard already uses accepted current-holding issue classes:
  - `yield_accrual`
  - `coverage_gap`
  - `history_flags`
  - `missing_replay_point`
- this mismatch makes `aManWETH` look like a move-basis defect even when the
  tail is only passive rebasing accrual since the last materialized tx

## Scope

In scope:

- canonical `BRIDGE_OUT` source normalization for audited route-funded bridge
  starts
- `asset-ledger` current uncovered diagnostics parity with dashboard issue
  policy
- on-chain canonical rerun for affected rows and downstream replay rebuild

Out of scope:

- generic route-funded normalization for arbitrary unsupported routers
- new realized-PnL modeling for asset-changing bridge settlement
- non-current bucket diagnostics

## Accepted Policy

### Route-Funded Bridge Source

For audited routed bridge-start source txs such as:

- `swapAndStartBridgeTokensViaSquid(...)`
- `swapAndStartBridgeTokensViaStargate(...)`
- `swapAndStartBridgeTokensViaMayan(...)`
- route-tagged `LI.FI / Jumper` bridge starts with positive tx `value`

when the source row proves:

- `BRIDGE_OUT`
- exactly one outbound token principal leg
- exactly one outbound native leg that exactly matches tx `value`
- optional native gas fee as separate fee evidence

then canonical flows must be:

- token principal leg -> `TRANSFER`
- tx-value native funding leg -> `FEE`
- gas fee leg -> `FEE`

The tx-value native leg is route funding / route cost, not a second principal
transfer.

### Asset-Ledger Current Diagnostics

`asset-ledger current.uncoveredBuckets[*].uncoveredReason` must align to the
same issue classes already accepted by dashboard reads:

- `yield_accrual`
- `coverage_gap`
- `history_flags`
- `missing_replay_point`

## Acceptance Criteria

1. Routed bridge source rows with one token principal plus one tx-value native
   funding leg normalize to one principal `TRANSFER` plus native `FEE`.
2. Asset-changing bridge settlement replay can consume the repaired canonical
   source row without new special replay heuristics.
3. Destination `BRIDGE_IN` for the audited Squid/Jumper-style route receives
   carried source cost basis instead of staying fully uncovered.
4. Current `asset-ledger` uncovered diagnostics return `yield_accrual` for
   clean interest-bearing receipt drift such as `aManWETH`.
5. On-chain rerun can rebuild canonical rows and downstream replay state
   without touching immutable raw or Bybit evidence.

## Backend Tasks

1. `BE-R45-01` Add view-aware canonical flow shaping for audited route-funded
   `BRIDGE_OUT` source rows.
2. `BE-R45-02` Keep bridge-start typing unchanged while downgrading tx-value
   native route funding from `TRANSFER` to `FEE`.
3. `BE-R45-03` Add classifier regression coverage for Squid/Jumper-style source
   tx with:
   - one outbound token principal
   - one native tx-value funding leg
   - no inbound movement
4. `BE-R45-04` Add replay regression proving that an asset-changing bridge
   settlement source row with `TRANSFER + FEE` still restores destination basis.
5. `BE-R45-05` Align `asset-ledger` uncovered diagnostics with dashboard issue
   classes and add regression for `yield_accrual`.
6. `BE-R45-06` Run an on-chain canonical rerun:
   - reset affected on-chain `raw_transactions.normalizationStatus` to `PENDING`
   - clear affected on-chain `normalized_transactions`
   - clear downstream derived collections
   - keep immutable raw / integration evidence

## Architecture Notes

- This slice moves the fix earlier in the pipeline because the wrong
  `TRANSFER/FEE` split is canonical accounting meaning, not a replay ordering
  artifact.
- `asset-ledger` and dashboard current diagnostics must use the same issue
  vocabulary; otherwise UI reads imply different financial meanings for the
  same bucket.

## Completion Notes

- audited route-funded bridge source rows now normalize with one principal
  `TRANSFER` plus native route-funding `FEE`
- `asset-ledger` current uncovered diagnostics now expose dashboard-parity
  values such as `yield_accrual`
- rerun should start from on-chain normalization because canonical flow shape
  changed
