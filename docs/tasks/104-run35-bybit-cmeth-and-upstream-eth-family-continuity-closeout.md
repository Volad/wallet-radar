# Run 35 — Bybit CMETH And Upstream ETH-Family Continuity Closeout

## Goal

Repair the remaining ETH-family AVCO distortion by fixing:

1. Bybit `ETH -> CMETH` liquid-staking semantics.
2. Upstream `yvvbETH -> vbETH` continuity carry on Katana.

## Problem

Two different defects still distort ETH-family continuity after the replay-only
pass-through corridor fix:

1. Bybit funding-history `On-chain Earn subscription` rows currently normalize
   as two independent `VAULT_DEPOSIT` rows:
   - principal `ETH -...`
   - receipt asset `CMETH +...`
   This is inconsistent with the already accepted `ETH -> METH`
   liquid-staking policy and can leave a positive receipt leg mislabeled as a
   one-leg vault deposit.
2. Upstream KATANA `VAULT_WITHDRAW yvvbETH -> vbETH` continuity loses almost
   the full carried basis because `YVVBETH` is outside the audited `ETH`
   family. Replay therefore fails to match the outbound wrapper leg with the
   inbound receipt wrapper leg.

## Target Policy

1. Bybit `Earn / On-chain Earn subscription` rows in the audited `ETH` family
   follow the same policy as audited `ETH -> METH` liquid staking:
   - canonical normalized type is `STAKING_DEPOSIT`
   - principal and derivative legs persist as continuity `TRANSFER`
   - no realized PnL on the conversion itself
2. Audited upstream vault receipt wrappers `yvvbETH -> vbETH` remain continuity
   only:
   - carried basis moves from `yvvbETH` into `vbETH`
   - replay may preserve any quantity mismatch as uncovered tail
   - the conversion must not zero out cost basis on the inbound wrapper
3. The shared audited `ETH` family expands to include:
   - `CMETH`
   - `YVVBETH`

## Scope

In scope:

- Bybit funding-history extraction for audited `ETH -> CMETH`
- Bybit normalization of paired liquid-staking rows
- shared `ETH` family support for replay continuity
- replay regression for `yvvbETH -> vbETH`
- rerun preparation for updated normalization / replay

Out of scope:

- unaudited Bybit earn receipt wrappers outside the accepted `ETH` family
- generic policy changes for all vault receipt tokens
- UX changes for covered / uncovered quantity display

## Acceptance Criteria

1. Bybit funding-history `Earn / On-chain Earn subscription` rows for audited
   `ETH`-family symbols extract as `STAKING_DEPOSIT`, not `VAULT_DEPOSIT`.
2. Paired Bybit `ETH -> CMETH` rows normalize into one confirmed
   `STAKING_DEPOSIT` transaction with both legs as `TRANSFER`.
3. A positive `CMETH` receipt leg may not persist as a standalone negative
   `VAULT_DEPOSIT` transfer row.
4. `AccountingAssetFamilySupport` recognizes both `CMETH` and `YVVBETH` as
   `FAMILY:ETH`.
5. Replay preserves carried cost basis across `VAULT_WITHDRAW yvvbETH -> vbETH`
   and only the quantity mismatch remains uncovered.
6. Rerun-ready Mongo preparation updates affected Bybit staging rows and clears
   downstream normalized / replay state without deleting immutable raw evidence.

## Implementation Tasks

1. Expand the audited `ETH` accounting family with `CMETH` and `YVVBETH`.
2. Update Bybit funding-history extraction so audited `On-chain Earn
   subscription` rows in the `ETH` family emit `STAKING_DEPOSIT`.
3. Generalize Bybit liquid-staking normalization from legacy `ETH 2.0`-only
   naming to the broader audited liquid-staking pair policy.
4. Add regressions for:
   - Bybit `CMETH` extraction
   - Bybit `ETH -> CMETH` normalized pair
   - replay `yvvbETH -> vbETH` carry preservation
5. Prepare Mongo for rerun:
   - patch affected `bybit_extracted_events`
   - delete downstream derived state that must be recomputed
   - keep immutable raw evidence
6. Restart backend and verify the session re-enters normalization / replay.

## Expected Outcome

After rerun:

- Bybit `ETH -> CMETH` no longer leaks as two contradictory vault-deposit rows
- upstream wrapper continuity keeps carried ETH-family basis through
  `yvvbETH -> vbETH`
- ETH-family AVCO on later Arbitrum / Bybit / Mantle paths is driven by real
  remaining coverage gaps instead of these two avoidable continuity defects
