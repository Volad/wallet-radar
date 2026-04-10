# 125 — Bybit Same-Universe Continuity Repair Closeout

Date: 2026-04-09
Owner slice: backend / clarification / accounting
Source audit: `results/stats/20/internal-transfer-focus.md`

## Problem

Audited wallet `<-> Bybit` same-universe corridors could still lose move-basis
continuity after an on-chain-only rerun.

Primary audited case:

- `0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27`

Counter-example that must stay unmatched:

- `0xcce37c54e31867ed9bca2f34ffbfdbb62329811896b9e60f2ae087a4d2b41e67`

Observed live failure:

- the Bybit canonical leg still carried `correlationId = BYBIT:ARBITRUM:<txHash>`
- the rebuilt on-chain wallet leg no longer carried that metadata
- replay therefore disposed the wallet inventory and opened zero-basis venue
  inventory instead of carrying cost basis through the same owner scope

## Root cause

This is not a transfer-type bug. It is a post-rerun continuity-restoration gap.

- `BybitNormalizationService` can mark both sides when the exact on-chain leg is
  present during Bybit normalization
- but later on-chain-only reruns delete and rebuild the wallet-side canonical
  row
- no clarification-time repair re-attached the already proven Bybit match onto
  the rebuilt on-chain row

## Decision

Keep wallet `<-> Bybit` movements owner-agnostic at the canonical type layer:

- do not promote them into persisted `INTERNAL_TRANSFER`
- restore exact continuity metadata on both sides during clarification
  post-processing

Repair policy:

1. candidate row is a confirmed on-chain `EXTERNAL_TRANSFER_IN/OUT`
2. exact `BYBIT` canonical row exists for the same `txHash + networkId`
3. directions are reciprocal
4. quantities are compatible within replay-safe tolerance
5. principal continuity family is the same
6. wallet ref and `BYBIT:<uid>` share one `accounting_universe`
7. then materialize / restore:
   - `correlationId = BYBIT:<NETWORK>:<txHash>`
   - `continuityCandidate = true`
   - `matchedCounterparty` on both rows

## Implemented scope

### P0 clarification repair

- add `BybitTransferContinuityRepairService`
- run it from `ClarificationPostProcessingHandler`
- scope:
  - on-chain row stays canonical `EXTERNAL_TRANSFER_*`
  - Bybit row stays canonical `EXTERNAL_TRANSFER_*`
  - only continuity metadata is repaired

### P0 safety guards

- require a real normalized Bybit row for the same `txHash + networkId`
- require exact same-universe membership between wallet and Bybit integration
- require family-compatible principal flow and bounded quantity tolerance
- skip if multiple compatible Bybit rows exist

### P0 explicit exclusion of dust/spam

- rows such as `0xcce37c54e31867ed9bca2f34ffbfdbb62329811896b9e60f2ae087a4d2b41e67`
  remain external because they have no real Bybit canonical peer

## Acceptance criteria

- audited hash `0xbc3fe1a56b06077185272a29beb16fda87fcf4c26049f0d6e13785a6b658ce27`
  rebuilds with:
  - on-chain wallet row still `EXTERNAL_TRANSFER_OUT`
  - Bybit row still `EXTERNAL_TRANSFER_IN`
  - both rows share deterministic `BYBIT:ARBITRUM:<txHash>` correlation
  - replay carries basis into `BYBIT:33625378`
- audited dust hash
  `0xcce37c54e31867ed9bca2f34ffbfdbb62329811896b9e60f2ae087a4d2b41e67`
  stays unmatched
- no canonical wallet `<-> Bybit` row is promoted into `INTERNAL_TRANSFER`
