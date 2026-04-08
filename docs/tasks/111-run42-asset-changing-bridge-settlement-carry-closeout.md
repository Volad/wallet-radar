# 111 — Run 42 Asset-Changing Bridge Settlement Carry Closeout

Status: Done

## Goal

Close the dominant remaining ETH coverage gap after the linked same-family
bridge replay repair.

The corrected diagnosis is:

- the current live ETH uncovered tail is no longer dominated by missing
  same-family bridge carry
- the largest remaining hole comes from already-linked asset-changing route
  pairs such as:
  - `USDC -> ETH`
  - `USDt -> ETH`
- those rows are correctly normalized as `BRIDGE_OUT -> BRIDGE_IN`, but replay
  still left the destination asset fully uncovered because `continuityCandidate`
  remained `false`

## Problem Statement

The previous downstream audit overstated same-family bridge misses because late
bridge carry attachment is currently traced under the source-side tx hash in
`asset_ledger_points`. That trace shape made some repaired `CARRY_IN` rows look
missing when the carry had in fact already been applied.

After re-checking live Mongo and the current replay trace, the remaining
high-impact ETH gap proved to come from asset-changing route settlements:

- `0xda7d556e31b44869f80954ca6b81217bd14fc61767dda791c00ef6e60a558de7`
  (`UNICHAIN BRIDGE_OUT USDC -2050.040045`)
  ->
  `0xc0aaf96b2cffe3582bed2387854f2569b3ad0e99e92daea46126c8824aa5712c`
  (`KATANA BRIDGE_IN ETH +0.452894410848733888`)
- `0x42ab47215cb6f87e90fe27f3e1ed70808b8257972b82182b5f1c5e0e924754c4`
  (`AVALANCHE BRIDGE_OUT USDt -1199.166831`)
  ->
  `0xd54c44df3acd95e8eda1184eee810a03cabd6f3e7b52cfdf210e82fae45e8d2c`
  (`LINEA BRIDGE_IN ETH +0.267547430473578243`)

Facts proven in live data:

- both pairs already share deterministic `correlationId`
- both already have exact `matchedCounterparty`
- both intentionally remain `continuityCandidate = false`
- source asset and destination asset differ

Current replay behavior before this slice:

- source-side quantity and source-side cost leave the source bucket
- destination-side quantity materializes as uncovered transfer-in
- downstream custody / lending rows faithfully propagate that uncovered tail

This is why the ETH gap now survives even after same-family bridge carry and
Aave family-equivalent custody repairs.

## Scope

In scope:

- replay-only deterministic settlement repair for already-linked
  asset-changing bridge route pairs
- docs correction for the latest audit diagnosis
- regression tests
- replay-only rerun after implementation

Out of scope:

- changing canonical bridge normalization types
- enabling `continuityCandidate = true` on asset-changing routes
- synthesizing explicit realized PnL for non-stable source asset disposal
- pricing-route FMV reconstruction from provider route payloads

## Acceptance Criteria

1. Already-linked `BRIDGE_OUT -> BRIDGE_IN` pairs with
   `continuityCandidate = false` may enter a dedicated replay settlement lane.
2. The lane is keyed only by deterministic route evidence:
   - shared `correlationId`
   - exact `matchedCounterparty`
   - one principal transfer out on the source row
   - one principal transfer in on the destination row
3. Replay reallocates source carried cost basis into the destination asset
   instead of leaving the destination fully uncovered.
4. Destination covered quantity is computed from source covered-share ratio:
   `coveredSourceQty / totalSourceQty`.
5. Fully covered source route:
   - destination becomes fully covered.
6. Partially covered source route:
   - destination inherits the same covered / uncovered proportion.
7. Inbound-first chronology remains safe:
   - inbound quantity materializes immediately
   - later source carry attaches without duplicating quantity.
8. Ambiguous route keys remain unresolved rather than guessed.

## Backend Tasks

1. `BE-R42-01` Add dedicated asset-changing bridge settlement replay lane in
   `AvcoReplayService`.
2. `BE-R42-02` Preserve existing same-family linked bridge lane and keep both
   paths disjoint by `continuityCandidate`.
3. `BE-R42-03` Add regression tests for:
   - fully covered `stable -> ETH` route
   - partially covered source route
   - inbound-first asset-changing settlement
4. `BE-R42-04` Prepare replay-only rerun:
   - keep raw / integration / normalized evidence
   - clear derived replay outputs only

## Architecture Notes

This slice intentionally uses a conservative book-value settlement model:

- destination acquisition cost = source carried cost basis
- destination covered ratio = source covered ratio
- no explicit realized PnL is synthesized for source disposal yet

Trade-off:

- this materially improves destination cost basis and AVCO coverage now
- but it does not yet provide market-consistent realized PnL for non-stable
  source assets such as `CAKE -> BNB`

That richer treatment remains a later follow-up once canonical route
settlement pricing is modeled explicitly.

## Completion Notes

- Added replay-only route-settlement carry repair for already-linked
  asset-changing `BRIDGE_OUT -> BRIDGE_IN` pairs.
- Same-family bridge carry lane remains unchanged and still requires
  `continuityCandidate = true`.
- Live ETH gaps caused by `USDC -> ETH` / `USDt -> ETH` routes can now be
  covered from the source carried basis instead of propagating zero-cost
  inbound quantity into later custody buckets.
