# 84 — Run 7 Transfer Chronology And Basis Attachment Closeout

## Context

Run 7 audit proved that the live `ETH-family` total is now economically close to
current on-chain state, but several `reconciled_holdings` rows still remain
`MISMATCH` because replay is not chronology-safe for continuity transfers.

Confirmed audit evidence:

1. `reconciled_holdings.currentQuantity` is already close to live on-chain
   `ETH-family` total.
2. The largest remaining non-yield mismatches are on `Katana`, `Unichain`,
   `Optimism`, and `zkSync`.
3. `asset_positions` still retains stale transfer buckets after later canonical
   outflows should have depleted them.
4. The main replay defect is reproducible when the destination-side continuity
   row is encountered before the matched source-side carry row.

Reference hashes from the audit:

- `0x58f37c3d516e1526b9696ae1a2fa6cbd00a723df00d55d7bc226cef96368d45d`
- `0xf4c41d791410dec7e76ad4681db9be8aaa22abb17e23e7828b6f619da4db9b09`
- `0xc0aaf96b2cffe3582bed2387854f2569b3ad0e99e92daea46126c8824aa5712c`
- `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`
- `0x7d6cf6ffdeec51865a5f516513e25312387f66ff39a194d0c474526bd8b4ffc1`
- `0x8c03a4efbd268e0b70659944359cf53963be6a95f046201b5204d27bff0519e4`

This slice fixes the replay chronology defect first. It does not attempt to
close every remaining mismatch source in one change.

## Architect Decision

Keep the current collection responsibilities unchanged:

- `asset_positions`
  internal replay state and basis source
- `on_chain_balances`
  current live quantity evidence
- `reconciled_holdings`
  current holdings read model

Do not solve the current mismatch surface by mutating read-model semantics.
Fix the replay engine instead.

Replay rules for continuity transfers:

1. If an inbound same-universe continuity row is encountered before its matched
   source carry, replay must materialize destination quantity immediately.
2. That immediately materialized quantity must be spendable by later canonical
   outflows in the same replay run.
3. A later matched source carry attaches cost basis to the already-materialized
   destination position.
4. That later attachment must not create destination quantity a second time.
5. `asset_positions` remains internal replay output and may still differ from
   live balances for policy reasons such as yield accrual.

## BA Scope / Acceptance Criteria

### DoD

1. Replay is chronology-safe for same-universe continuity transfers.
2. If an inbound continuity row appears before the matched source carry,
   destination quantity is materialized immediately.
3. Later canonical outflows from that destination wallet consume the
   materialized quantity in the same replay run.
4. When the matched source carry arrives later, replay attaches basis to the
   existing destination position without adding duplicate quantity.
5. A matched late carry clears the temporary unresolved marker created by that
   inbound quantity materialization.
6. Existing source-first continuity paths keep working unchanged.
7. End-of-replay synthetic quantity backfill is no longer used for inbound
   continuity rows.

### Edge Cases

- Case: source row arrives before destination row.
  Expected: existing carry-over semantics remain unchanged.

- Case: destination row arrives before source row in the same logical custody
  move.
  Expected: destination quantity exists immediately and later carry attaches
  basis only.

- Case: destination inbound remains unmatched by the end of replay.
  Expected: quantity remains present, but the position stays incomplete /
  unresolved.

- Case: a later canonical outflow spends that unmatched inbound quantity.
  Expected: replay reduces quantity immediately instead of resurrecting stale
  quantity at the end of replay.

- Case: bridge-family carry transfers cost across different but policy-equivalent
  assets with quantity drift.
  Expected: later carry still attaches full carried cost without minting
  duplicate destination quantity.

### In Scope

- `AvcoReplayService` continuity-transfer ordering
- temporary unresolved marker resolution for late source carry attachment
- regression tests
- docs and operator guidance

### Out Of Scope

- introducing explicit owner/accounting-universe custody semantics independent
  of session membership
- aToken / interest-bearing accrual policy
- `sessionId` lineage on derived collections
- full `zkSync` wrapper-chain remediation

## Backend Tasks

1. `BE-84-01` Make continuity replay chronology-safe
   - inbound first: materialize quantity immediately
   - source later: attach basis only
   - remove duplicate quantity backfill path

2. `BE-84-02` Add regression coverage for inbound-before-source custody moves
   - tracked-wallet transfer ordering
   - unmatched inbound that is later spent

3. `BE-84-03` Preserve current source-first and bridge-family carry behavior
   - no regression for Bybit -> on-chain carry
   - no regression for quantity-drift bridge-family carry

4. `BE-84-04` Update docs and operator guidance
   - `asset_positions` remains internal replay state
   - chronology-safe continuity replay becomes explicit policy

## Follow-Up Backlog

1. Introduce owner/accounting-universe aware custody semantics independent of
   session membership, instead of relying on `EXTERNAL_TRANSFER_IN/OUT`
   interpretation alone.
2. Distinguish principal replay quantity from live yield accrual for
   interest-bearing holdings such as `aManWETH`.
3. Add `sessionId` lineage to derived collections used in operator audit.
4. Continue the `zkSync ETH/WETH/aZksWETH` wrapper continuity remediation.

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve raw evidence and `historical_prices`
3. rerun the pipeline
4. re-audit:
   - `Katana ETH`
   - `Unichain ETH`
   - `Optimism WETH`
   - stale residual transfer buckets after the hashes listed above
