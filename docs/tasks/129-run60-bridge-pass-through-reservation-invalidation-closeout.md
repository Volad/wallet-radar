# 129 — Bridge Pass-Through Reservation Invalidation Closeout

Date: 2026-04-09
Owner slice: backend / accounting replay
Source audit: `results/stats/22/full-pipeline-audit.md`

## Problem

`ETH uncovered` regressed after the previous continuity fixes even though the
Mantle and Aave rows themselves were already materially correct.

The audited failing lineage was:

- `0x1e15cc64c0b6f5648be351a9567346cffdac842a5843c9c6fccc730bedc693a7`
- `0xbbed8204090b1c4e04cc0d107e9310213b1a49fa46bcc0a001d00a3c330cccc1`
- `0x656e66ba183ee29385cb541f78d7ed490c548f3a58d6bb2f9884d81048c9175f`
- `0x63419d37b01f3be37874de420b75712f06efefbff7238f3e3d542dadce5378de`

Before the fix:

- `BRIDGE_IN` on `UNICHAIN` reserved its incoming carry slice
- a later local covered `SWAP BUY ETH` increased the same wallet/network pool
- a later same-wallet `BRIDGE_OUT` still consumed only the stale reserved slice
- the destination `BRIDGE_IN` inherited only that tiny old carry
- the newly acquired covered basis stayed stranded on the source wallet

This was a replay reservation bug, not a bridge classification bug.

## Root cause

Replay pass-through corridors were too sticky.

The planner allowed:

- inbound reservation from `BRIDGE_IN`
- later outbound consumption by `BRIDGE_OUT`

but it did not invalidate the open reservation when an intervening
principal-affecting transaction had already touched the same wallet/network
asset bucket.

That meant replay could preserve an old isolated bridge slice even after the
position had already been mixed by local acquisitions.

## Decision

Do not remove bridge-driven pass-through corridors entirely.

They are still required for valid immediate custody paths such as:

- `BRIDGE_IN -> LENDING_DEPOSIT`
- `BRIDGE_IN -> VAULT_DEPOSIT`
- `BRIDGE_IN -> STAKING_DEPOSIT`
- `BRIDGE_IN -> PROTOCOL_CUSTODY_DEPOSIT`
- `BRIDGE_IN -> LP_ENTRY`

Instead:

1. keep bridge inbound rows eligible as pass-through candidates
2. keep bridge outbound rows eligible as pass-through consumers
3. invalidate any open reservation at the first later principal-affecting row
   in the same scope and asset bucket unless that row is the unique matched
   paired outbound/custody leg
4. fall back to pooled AVCO once the reservation has been invalidated

## Implemented scope

### P0 replay planner invalidation

- `AvcoReplayService.buildPassThroughCorridorPlan(...)` now tracks principal
  touches by pass-through scope key
- any open inbound reservation is discarded when a later same-scope
  principal-affecting row appears before the paired outbound/custody leg

### P0 preserved approved behavior

- `BRIDGE_IN -> custody deposit` remains eligible when it is the immediate
  deterministic next principal touch in scope
- Bybit transit corridors still reserve venue-local carried basis when no
  intervening same-bucket principal touch breaks the lane

### P0 regression coverage

- added a replay test proving that `BRIDGE_IN -> local acquisition -> BRIDGE_OUT`
  must use the pooled source position instead of a stale reserved inbound slice
- preserved the existing replay test for
  `BRIDGE_IN -> LENDING_DEPOSIT` custody pass-through

## Acceptance criteria

- same-wallet same-network `BRIDGE_IN -> local principal acquisition -> BRIDGE_OUT`
  must no longer strand newly acquired covered basis on the source wallet
- destination `BRIDGE_IN` of that later bridge pair must inherit pooled carried
  basis, not only the old bridge-reserved slice
- audited immediate custody path `BRIDGE_IN -> LENDING_DEPOSIT` must remain
  intact
- existing Bybit transit corridor behavior must remain intact

## Detailed task breakdown

### Task A — replay rule update

- restore bridge rows as eligible pass-through participants
- add invalidation on the first intervening same-scope principal touch

### Task B — regression tests

- keep prior `bridgeInboundCorridorFeedsLendingDepositBeforeSpotPooling`
  behavior green
- add explicit regression for later bridge-out after local acquisition

### Task C — rerun and verification

- rerun the on-chain canonical slice
- verify the audited `UNICHAIN -> ZKSYNC` bridge chain
- compare `ETH uncovered` before/after

## Risks and guardrails

- do not disable bridge pass-through globally; that would regress valid custody
  continuity
- do not keep a reservation alive across local acquisitions, swaps, LP exits,
  rewards, or any other principal-affecting event in the same scope
- do not change canonical bridge typing; this is replay-only behavior

## Expected outcome

After rerun:

- stale bridge-reserved slices no longer survive after bucket-mixing local
  activity
- later same-wallet bridge-outs consume the real pooled source position
- destination bridge carry becomes materially closer to the true upstream
  covered basis
