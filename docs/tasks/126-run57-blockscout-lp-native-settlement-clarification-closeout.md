# 126 — Blockscout LP Native Settlement Clarification Closeout

Date: 2026-04-09
Owner slice: backend / normalization / clarification / accounting
Source audit: `results/stats/22/full-pipeline-audit.md`

## Problem

`ETH` uncovered regressed because several `BLOCKSCOUT`-backed LP exit rows were
materialized with incomplete movement evidence.

Primary audited case:

- `0x6b57e6439d1bcde7faaff2f43498ef97be9e696f889aeef2b2cc68fa2a5a1cf3`

Observed live failure:

- canonical row was `LP_EXIT`
- wallet-scoped explorer evidence only persisted `USDC` plus gas
- missing `ETH` settlement leg never entered replay
- the next wrap
  `0xbdf26819493244fc76cc7fa9714f8788770e4f991f4285df81ef9af1974cc45c`
  therefore opened uncovered `WETH`

This was not a replay bug and not a `WRAP` bug. It was an upstream transfer
evidence gap.

## Root cause

For audited `Blockscout` networks, wallet-scoped explorer pages can miss
transaction-internal native payouts even when the transaction-level
`/internal-transactions` endpoint already exposes them.

Audited proof on `BASE`:

- wallet-scoped raw row for `0x6b57...` had `explorer.internalTransfers = []`
- transaction-level Blockscout endpoint proved:
  - `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364 -> wallet`
  - `value = 546279273895213849`

The row was therefore economically known (`LP_EXIT`) but not movement-complete.

## Decision

Do not fabricate basis at replay time.

Do not hardcode per-hash native settlement legs.

Instead:

1. keep the initial LP family detection
2. route audited `BLOCKSCOUT` LP exit / fee-claim candidates into
   `PENDING_CLARIFICATION`
3. let full-receipt clarification fetch tx-level transfer evidence from the same
   explorer family
4. rebuild canonical rows from enriched raw evidence

Important orchestration constraint:

- these candidates must bypass metadata-only clarification
- otherwise metadata clarification can remove the pending reason and advance the
  row without ever persisting the tx-level `internalTransfers`

## Implemented scope

### P0 classification gate

- add `NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED`
- detect `BLOCKSCOUT` `LP_EXIT` / `LP_FEE_CLAIM` rows that:
  - have contract-call LP exit shape
  - still have no explorer internal transfers
  - still have no persisted full-receipt clarification evidence
  - still do not carry an inbound native / wrapped-native principal leg
- keep the canonical type, but send the row to
  `PENDING_CLARIFICATION`

### P0 receipt-clarification allowlist

- extend receipt-clarification candidate loading to this new reason
- allow full-receipt clarification for these rows only on `BLOCKSCOUT` raw
  evidence
- exclude this reason from metadata-clarification batch selection

### P0 same-source recovery policy

- recovery stays same-source:
  - `BLOCKSCOUT` raw -> `BLOCKSCOUT` tx-level clarification
- no RPC-only enrichment is used for this lane

## Acceptance criteria

- audited hash
  `0x6b57e6439d1bcde7faaff2f43498ef97be9e696f889aeef2b2cc68fa2a5a1cf3`
  must first materialize as:
  - `type = LP_EXIT`
  - `status = PENDING_CLARIFICATION`
  - reason contains `NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED`
- after clarification, the same hash must rebuild with:
  - `type = LP_EXIT`
  - positive native `ETH` settlement leg present
  - reason removed
- downstream wrap
  `0xbdf26819493244fc76cc7fa9714f8788770e4f991f4285df81ef9af1974cc45c`
  must no longer open uncovered `WETH` only because the upstream LP exit missed
  its native settlement leg
- stable / token-only LP exits may still be clarified in this lane, but only
  when they meet the bounded `BLOCKSCOUT` candidate shape
