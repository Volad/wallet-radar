# Run 32 — Bybit Funding History Parity Closeout

## Goal

Restore practical parity between the new API-driven Bybit acquisition path and
the historical `external_ledger_raw` baseline, with primary focus on the old
`fund_asset_changes` lane.

## Problem

The new integration-first Bybit backfill reached parity for:

- `uta_derivatives`
- `withdraw_deposit`

But it significantly under-collected the old `fund_asset_changes` domain.

Observed gap versus the archived baseline:

- old `external_ledger_raw.fund_asset_changes`: `2471`
- new `bybit_extracted_events.sourceFileType=fund_asset_changes`: `516`

Missing classes were concentrated in:

- flexible-savings interest distributions
- launchpool yield / subscription / withdrawal
- convert rows
- trading-bot transfers
- loan-side funding-account events

The root cause was architectural:

- the new backfill did not use Bybit's `Funding Account Transaction History`
  endpoint
- instead it tried to rebuild `fund_asset_changes` from a mix of narrower
  endpoints (`internal transfer`, `universal transfer`, `convert`, `earn
  order history`)
- that mix does not cover the same event surface as the funding-account ledger

## Target Policy

1. `Funding Account Transaction History` is the primary API replacement for the
   legacy `fund_asset_changes` export.
2. Shared Bybit backfill must treat this lane as first-class acquisition
   evidence, not as optional enrichment.
3. Bybit extraction must rebuild `fund_asset_changes` semantics primarily from
   `FUNDING_HISTORY`, not by stitching multiple narrower endpoints.
4. Narrow funding endpoints remain optional future enrichment lanes; they do
   not own the canonical `fund_asset_changes` reconstruction path.
5. Bybit normalization still consumes `bybit_extracted_events`; no provider
   clarification lane is introduced.

## Scope

In scope:

- add Bybit `FUNDING_HISTORY` stream to shared integration backfill
- fetch `/v5/asset/fundinghistory` in 7-day windows
- map funding-history rows into `bybit_extracted_events` with old-style
  `fund_asset_changes` semantics
- stop planning duplicate funding-account streams for the initial backfill
  pass
- preserve `uta_derivatives` and `withdraw_deposit` lanes

Out of scope:

- deleting legacy `external_ledger_raw`
- deleting old Mongo data
- provider support beyond Bybit
- perfect semantic parity for every obscure funding-history description in one
  slice

## Acceptance Criteria

1. Shared Bybit backfill plans `FUNDING_HISTORY` segments with the provider's
   7-day window limit.
2. `BybitApiClient` fetches `/v5/asset/fundinghistory` using
   `createTimeFrom/createTimeTo` in seconds and cursor pagination.
3. `BybitExtractionService` maps funding-history rows into
   `sourceFileType=fund_asset_changes`.
4. The extracted mapping restores the main historical classes:
   - `Earn`
   - `Convert`
   - `Transfer in`
   - `Transfer out`
   - `Bot`
   - `Deposit`
   - `Withdraw`
   - `Airdrop`
   - `Loans`
5. Planner no longer relies on `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`,
   `CONVERT_HISTORY`, or `EARN_FLEXIBLE_SAVING` as primary initial-backfill
   sources for `fund_asset_changes`.
6. Existing `uta_derivatives` and `withdraw_deposit` acquisition remains
   intact.
7. Targeted tests cover the new planner, client parameterisation, and funding
   history extraction mapping.

## Tasks

### BA-101-01 Requirements

1. Freeze the source-of-truth rule:
   - old `fund_asset_changes` parity must come from Bybit funding-account
     history first
2. Freeze the duplicate-control rule:
   - narrower funding endpoints must not create duplicate primary staging rows
     when the funding-account ledger already owns that semantic lane

### SA-101-02 Architecture

1. Keep shared backfill orchestration unchanged.
2. Add one provider-specific stream:
   - `FUNDING_HISTORY`
3. Keep provider layering:
   - `integration_raw_events`
   - `bybit_extracted_events`
   - `normalized_transactions`

### BE-101-03 Backend

1. Add `FUNDING_HISTORY` to the Bybit stream model.
2. Add Bybit API client support for `/v5/asset/fundinghistory`.
3. Add funding-history extraction mapping to rebuild `fund_asset_changes`.
4. Adjust initial Bybit planner so funding-account parity no longer depends on
   duplicate narrow streams.
5. Add regression tests using archived parity expectations.

### FE-101-04 Frontend

No frontend work is required in this slice.

## Expected Outcome

After this slice:

- new Bybit sessions recover the missing `fund_asset_changes` surface far more
  closely to the archived baseline
- `Earn` / `Convert` / transfer / bot / loan funding-account rows are acquired
  from a single primary ledger endpoint
- shared integration backfill stays intact; only provider-specific segment
  planning and execution change
