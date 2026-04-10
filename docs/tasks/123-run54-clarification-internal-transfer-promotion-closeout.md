# 123 — Clarification Internal Transfer Promotion Closeout

Date: 2026-04-09
Owner slice: backend / accounting / clarification
Source audit: `results/stats/17/full-pipeline-audit.md`

## Problem

Run 17 confirmed that simple on-chain moves between tracked wallets in the same
owner scope were still persisted as:

- `EXTERNAL_TRANSFER_OUT`
- `EXTERNAL_TRANSFER_IN`

with:

- `matchedCounterparty`
- `continuityCandidate = true`

That left canonical semantics too weak for audit/debug and still allowed
one-sided tracked-wallet hints to masquerade as owner continuity.

Representative audited hashes:

- `0x1a2657ac7b825dfeba3ce36641ad1df3e4a2d1e20520492102e36f195c5bc8af`
- `0x55c0578c2b393de1f2477b575bccbf2f3b146401aef40f1a2fedda55ed297fac`

## Revalidation before implementation

The second P0 item from run 17 was rechecked on live data before this slice.

Result:

- the audited same-asset LI.FI / Across `USDC` pair
  `0x0136388ffdcde444886039751873af5d2d6da25ef4ca482d2c53978aa5afbcd4`
  -> `0x6b2516f93861268922f1ca01c9a96bf83c0e005ccc0fcb3b8c94f788cf667885`
  already carries basis under the current replay lane
- no new bridge replay patch was required in this slice

Remaining bridge tails are separate backlog:

- inherited upstream uncovered share
- family-equivalent wrapper/receipt provenance

## Decision

Do **not** persist `INTERNAL_TRANSFER` from tracked-wallet lookup alone.

Instead:

- keep initial normalization conservative
- run a clarification-time reciprocal pair promotion pass
- promote into canonical `INTERNAL_TRANSFER` only when an exact peer row already
  exists

This keeps the rule deterministic and prevents false internal labeling for
one-sided tracked-counterparty hints.

## Implemented scope

### P0 clarification-time promotion

- add `InternalTransferPairLinkService`
- scan on-chain confirmed generic transfer rows with:
  - `type in (EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT)`
  - `continuityCandidate = true`
  - reciprocal `matchedCounterparty`
  - no competing lifecycle metadata (`correlationId`, protocol route ownership)
- require both canonical wallet-local rows for the same `txHash + networkId`
- promote both rows to `INTERNAL_TRANSFER`
- retag principal flows to `TRANSFER`
- clear stale non-fee price fields on the promoted rows

### Guardrails

Required:

1. both rows already exist in `normalized_transactions`
2. `source = ON_CHAIN`
3. `status = CONFIRMED`
4. same `txHash`
5. same `networkId`
6. reciprocal `matchedCounterparty`
7. exactly one principal non-fee flow per row
8. one principal flow inbound, one outbound
9. matching principal family identity
10. matching quantity within a tiny transfer tolerance
11. blank `correlationId`
12. blank `protocolName`

If any guardrail fails:

- keep the row conservative as external

## Explicit non-goals of this slice

- no owner-scope inference from a single row
- no chart-only grouping changes
- no bridge replay rewrite
- no one-sided auto-heal for missing peer canonical rows

## Acceptance criteria

- audited reciprocal pairs such as
  - `0x1a2657ac7b825dfeba3ce36641ad1df3e4a2d1e20520492102e36f195c5bc8af`
  - `0x55c0578c2b393de1f2477b575bccbf2f3b146401aef40f1a2fedda55ed297fac`
  rebuild as canonical `INTERNAL_TRANSFER`
- principal flows on promoted rows are `TRANSFER`, not `BUY/SELL`
- replay preserves basis across the promoted pair without synthetic acquisition
  or disposal
- one-sided tracked-counterparty rows do not auto-promote
- on-chain rerun finishes without introducing new blocking review rows for this
  lane
