# 133 — Euler Native EVK And LI.FI Relay Routed Bridge Closeout

Date: 2026-04-09
Owner slice: backend / normalization + clarification
Source audit: `results/stats/30/full-pipeline-audit.md`

## Problem

Two audited canonical defects still degrade move-basis continuity and AVCO
truth:

1. Euler native EVK deposit
   `0x9ee1dd4856c2fb8847e167acea0ae983bb74d3206a0b87296b4bdc995bdc492f`
   rebuilds as `SWAP` instead of `LENDING_DEPOSIT`.
2. Routed same-asset LI.FI / Relay corridor
   `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6 ->
   0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8`
   rebuilds as `EXTERNAL_TRANSFER_OUT / EXTERNAL_TRANSFER_IN` instead of
   `BRIDGE_OUT / BRIDGE_IN`.

The audit also confirmed that the internal-transfer cluster from
`0xf4c41d...` through `0x616f32...` is already canonically correct and belongs
to a frontend-only follow-up, not this backend slice.

## Root Cause

### Euler

`EulerProtocolSemanticClassifier` currently expects simple deposit proof through
`shareInbound && principalOutbound`.

That is too narrow for audited native EVK deposits:

- principal leaves through tx-level native `value`
- share mint is visible on the receipt / explorer transfer surface
- protocol-local wrapper / vault hop is visible only after clarification

As a result, the protocol semantic hint is missed and generic `SWAP` heuristics
win.

### LI.FI / Relay

The source row already contains enough raw proof for bridge initiation:

- known `LI.FI Diamond`
- recovered selector from calldata
- positive native funding
- route tags such as `relay` and `jumper.exchange`

But the live runtime still does not guarantee `BRIDGE_OUT` on this exact shape.
Because the source row stays external, `LiFiBridgePairLinkService` never
materializes the Relay payout destination into `BRIDGE_IN`.

## Decision

Treat both issues as canonical normalization / clarification defects and prefer
a full on-chain rerun after implementation.

Rules:

1. Euler clarified native EVK deposits are plain lending continuity, not spot
   swap.
2. Known LI.FI route starts plus unique Relay payout destinations are bridge
   lifecycle, not generic external transfer noise.
3. The fix must remain bounded and protocol-backed; no generic proximity
   matcher is allowed.

## Acceptance Criteria

### P0 — Euler native EVK deposit

- `0x9ee1dd4856c2fb8847e167acea0ae983bb74d3206a0b87296b4bdc995bdc492f`
  rebuilds as `LENDING_DEPOSIT`
- `protocolName = Euler`
- the canonical principal leg is `ETH -> eWETH-1`
- no generic `SWAP` realization remains on that row

### P0 — LI.FI / Relay routed bridge corridor

- `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
  rebuilds as `BRIDGE_OUT`
- `0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8`
  rebuilds as `BRIDGE_IN`
- both rows share a deterministic `correlationId`
- both rows have `matchedCounterparty`
- same-asset ETH continuity is preserved across the pair

## Detailed Task Breakdown

### Task A — Euler native EVK semantic widening

- extend `EulerProtocolSemanticClassifier`
- support clarified native-value EVK deposit proof:
  - Euler batch / EVC receipt evidence
  - share mint to tracked wallet
  - positive native tx value
  - protocol-local wrapper / vault hop
- keep loop-router rules unchanged

### Task B — LI.FI bridge-start guarantee

- harden `BridgeStartClassifier` for audited LI.FI route starts where:
  - router is known `LI.FI Diamond`
  - selector is recovered from calldata
  - route tags prove LI.FI / Jumper routing
  - tx carries real funding
- add explicit regression around the audited BASE `0xae328590` shape

### Task C — Relay payout destination materialization

- extend `LiFiBridgePairLinkService`
- allow unique same-wallet Relay payout destination to materialize as
  `BRIDGE_IN`
- keep the rule bounded to:
  - source already proved as LI.FI route start
  - destination sender is registry-backed Relay infrastructure
  - same principal asset family
  - bounded time and quantity drift

### Task D — Rerun and verification

- rerun the full on-chain slice
- verify both audited cases on live `normalized_transactions` and
  `asset_ledger_points`
- compare `ETH` uncovered before / after to confirm no new regression was
  introduced by the canonical fixes

## Risks And Guardrails

- do not generalize Euler native deposit widening to arbitrary native tx with
  share mint; keep it inside Euler batch / EVC semantics only
- do not let LI.FI route tags on unknown routers produce `BRIDGE_OUT`
- do not promote arbitrary Relay payouts into `BRIDGE_IN` without one unique
  audited LI.FI source candidate
- prefer a full rerun over targeted repair because both fixes change canonical
  truth

## Expected Outcome

After rerun:

- Euler EVK native deposits stop realizing false swap PnL
- routed LI.FI / Relay same-asset bridges stop polluting history with external
  sell/buy pairs
- move-basis continuity becomes more accurate before replay reaches the later
  Mantle carrier bucket

## Implemented Scope

- widened `EulerProtocolSemanticClassifier` so clarified native-value EVK
  deposits may resolve as `LENDING_DEPOSIT` when the receipt proves:
  - one positive native funding leg
  - one share mint to the tracked wallet
  - one protocol-local fungible hop
- hardened `BridgeStartClassifier` so canonical brand `LI.FI` still qualifies
  as a route-proven source bridge start on known `LI.FI Diamond`
- widened `LiFiBridgePairLinkService` so a unique same-wallet Relay payout may
  materialize the destination `BRIDGE_IN` without official LI.FI status, but
  only from registry-backed settlement proof
- updated domain / architecture / accounting policy docs for both audited rules

## Tests

- `./gradlew --console=plain :backend:compileJava`
- `./gradlew --console=plain :backend:test --tests 'com.walletradar.ingestion.pipeline.classification.OnChainClassifierTest' --tests 'com.walletradar.ingestion.pipeline.clarification.LiFiBridgePairLinkServiceTest'`

Both passed.

## Operational Verification

- backend stopped
- full on-chain rerun reopened from raw:
  - `raw_transactions -> PENDING`: `3107`
  - deleted `normalized_transactions{source=ON_CHAIN}`: `3107`
  - deleted `asset_ledger_points`: `9012`
  - deleted `on_chain_balances`: `212`
- backend rebuilt and restarted with `docker compose up -d --build backend`

Live end state:

- `pipelineState = ACCOUNTING_REPLAY / COMPLETE`
- `normalizedOnChain = 3107`
- `normalizedBybit = 2544`
- `asset_ledger_points = 9013`
- `on_chain_balances = 212`

Audited row outcomes:

- `0x9ee1dd4856c2fb8847e167acea0ae983bb74d3206a0b87296b4bdc995bdc492f`
  now rebuilds as `LENDING_DEPOSIT / Euler / v1`
- `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
  now rebuilds as `BRIDGE_OUT / LI.FI`
- `0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8`
  now rebuilds as `BRIDGE_IN / Relay`
- the bridge pair now shares one deterministic
  `correlationId = bridge:lifi:0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
  and reciprocal `matchedCounterparty`

ETH coverage delta:

- before this slice: `ETH uncovered = 0.31609493403591954`
- after rerun: `ETH uncovered = 0.305236193079129995`
- improvement: `0.010858740956789545`

Remaining dominant tail:

- `MANTLE / AMANWETH`
- latest tx `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`
- uncovered `0.299613033684579534 ETH`

Non-backend follow-up confirmed by the same audit:

- the internal-transfer cluster from `0xf4c41d...` through `0x616f32...` is
  canonically correct
- the remaining issue there is a frontend grouped `from/to` display follow-up,
  not a normalization or replay defect
