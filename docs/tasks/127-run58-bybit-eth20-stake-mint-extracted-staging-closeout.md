# 127 — Bybit ETH 2.0 Stake/Mint Extracted Staging Closeout

Date: 2026-04-09
Owner slice: backend / bybit extraction / normalization / accounting
Source audit: `results/stats/22/full-pipeline-audit.md`

## Problem

`ETH` uncovered regressed sharply, and one audited upstream gap was still
hiding inside Bybit extracted staging.

Primary audited provider events:

- `9bc8c8a2c05ffc5ddeb536e68874ee33221d66d524cb9fce80d8e94e2bcc8ab0`
  - `FUNDING_HISTORY`
  - `showBusiTypeEn = ETH 2.0`
  - `descriptionEn = Stake`
  - `ETH -0.709`
- `d552dbd4b2b05a3681a536ba5cc95345ffb725ae4478647464589a9843d0c059`
  - `FUNDING_HISTORY`
  - `showBusiTypeEn = ETH 2.0`
  - `descriptionEn = Mint`
  - `METH +0.66865026`

Observed live failure before the fix:

- both extracted rows stayed `UNKNOWN_CEX / RAW`
- no canonical `ETH -> METH` liquid-staking row was materialized
- the later audited `METH -> CMETH`
  `662d68d6e81789ed579e5c4b26a3c187dc4467c2c8a15a36f36620d1c9a7552e|a854608d802df265474318c5440c898044a578daf3b47e09693b874ce87d8ee5`
  row therefore had to inherit incomplete upstream carry

This was an extracted-staging defect, not an Aave or Mantle-specific bug.

## Root cause

Two staged assumptions were too narrow:

1. `BybitExtractionService` did not preserve `showBusiTypeEn = ETH 2.0` as a
   deterministic `bybitType`, so the raw funding-history slice never entered
   the audited liquid-staking lane.
2. The audited liquid-staking pairer still required exact description equality.
   That works for `On-chain Earn subscription`, but it rejects the official
   `ETH 2.0 / Stake` + `ETH 2.0 / Mint` lifecycle even though the shared
   provider business family is already authoritative.

## Decision

Do not fabricate a replay-time repair.

Do not special-case the hash pair in accounting.

Instead:

1. preserve `ETH 2.0` at extraction time
2. classify audited `ETH 2.0` funding-history rows as `STAKING_DEPOSIT`
3. allow asymmetric descriptions inside this audited provider family while
   keeping exact-description matching for the existing `Earn` lifecycle slices

## Implemented scope

### P0 extraction preservation

- `BybitExtractionService` now maps `showBusiTypeEn = ETH 2.0` into
  `bybitType = ETH 2.0`
- the same slice keeps `canonicalType = STAKING_DEPOSIT`

### P0 audited pairer rule

- extracted and legacy Bybit liquid-staking pairers now keep exact description
  matching for ordinary earn lifecycles
- but the audited `ETH 2.0` slice may pair on the shared provider business
  family even when the two descriptions differ between `Stake` and `Mint`

### P0 rerun preparation

- reopen only the affected extracted rows for rerun
- rebuild canonical Bybit rows through the normal pipeline
- keep immutable provider raw evidence

## Acceptance criteria

- audited extracted rows
  `9bc8c8a2c05ffc5ddeb536e68874ee33221d66d524cb9fce80d8e94e2bcc8ab0`
  and
  `d552dbd4b2b05a3681a536ba5cc95345ffb725ae4478647464589a9843d0c059`
  must end as:
  - `bybitType = ETH 2.0`
  - `canonicalType = STAKING_DEPOSIT`
  - `status = CONFIRMED`
- normalization must materialize one canonical row:
  - `ETH -0.709`
  - `METH +0.66865026`
  - `type = STAKING_DEPOSIT`
- replay must carry non-zero `costBasisDeltaUsd` from `ETH` into `METH`
- downstream audited `METH -> CMETH` continuity must still preserve carried
  `costBasisDeltaUsd` rather than reopening a zero-cost source leg
- documentation must explicitly distinguish:
  - provider business-family evidence
  - human-readable description strings
  - deterministic normalization-time pairing

## Expected outcome

After rerun:

- the missing audited Bybit `ETH -> METH` lifecycle exists again as canonical
  accounting input
- move-basis continuity is restored through the immediate `ETH -> METH ->
  CMETH` chain
- any remaining large `ETH uncovered` tail can be investigated as a real
  upstream coverage problem instead of an extracted-staging omission
