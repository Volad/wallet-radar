# Run 30 — Bybit Extracted Staging Closeout

## Goal

Wire the new Bybit integration acquisition path into downstream normalization
without deleting legacy Mongo data.

## Problem

Run 29 introduced:

- session-owned Bybit integrations
- shared integration backfill segments
- immutable `integration_raw_events`

But canonical Bybit normalization still read only `external_ledger_raw`. That
left the new API-driven backfill disconnected from pricing, replay, and move
basis.

## Target Policy

1. Bybit provider enrichment stays entirely inside `BACKFILL`.
2. Backfill stores immutable provider payloads in `integration_raw_events`.
3. Backfill also materializes rebuildable Bybit-specific staging rows in
   `bybit_extracted_events`.
4. Canonical Bybit normalization drains `bybit_extracted_events` first.
5. Legacy `external_ledger_raw` remains migration-only fallback for older
   sessions until the old import runtime is deleted.
6. No post-normalization provider clarification lane exists for Bybit.
7. Existing Mongo data remains intact in this slice.

## Scope

In scope:

- Bybit extraction service from `integration_raw_events`
- execution-history, transfer, deposit, withdrawal, convert, and flexible-earn
  extraction
- extracted-first Bybit normalization runtime
- pipeline resume / telemetry / progress support for `bybit_extracted_events`

Out of scope:

- deleting `external_ledger_raw`
- deleting historical Bybit data from Mongo
- fully removing legacy Bybit normalization fallback
- providers beyond Bybit

## Acceptance Criteria

1. Bybit integration backfill persists immutable `integration_raw_events`.
2. The same backfill pass persists `bybit_extracted_events`.
3. Extracted events cover the currently supported Bybit acquisition streams:
   - transaction log
   - execution history
   - internal transfer
   - universal transfer
   - deposit / withdrawal
   - convert history
   - flexible savings earn history
4. Bybit normalization processes extracted rows before legacy
   `external_ledger_raw`.
5. Bridge rematch, telemetry, and session progress observe extracted staging.
6. Legacy sessions without extracted rows still work through the old
   `external_ledger_raw` fallback.
7. No Mongo cleanup is performed in this slice.

## Tasks

### BA-99-01 Requirements

1. Freeze the staged provider model:
   - immutable provider raw
   - rebuildable provider extracted staging
   - canonical normalized transactions
2. Freeze the runtime rule:
   - Bybit clarification belongs to backfill enrichment, not to normalization
3. Freeze the migration rule:
   - legacy external-ledger data remains readable until the new runtime is
     fully cut over

### SA-99-02 Architecture

1. Keep one shared backfill orchestration tree.
2. Add `bybit_extracted_events` as a rebuildable provider-owned staging layer.
3. Make Bybit normalization dual-lane:
   - extracted-first
   - legacy fallback
4. Keep provider-neutral raw storage and provider-specific extracted storage
   separated.

### BE-99-03 Backend

1. Save `bybit_extracted_events` during Bybit integration backfill.
2. Add extracted query and pairing services for:
   - trade pairing
   - convert clustering
   - ETH 2.0 staking pairing
   - transfer-shadow suppression
3. Switch `BybitNormalizationService` to process extracted staging first.
4. Update session progress, resume scheduler, and telemetry for extracted rows.
5. Add targeted tests for extraction and extracted-first normalization.

### FE-99-04 Frontend

No frontend work is required in this slice. Existing settings UI and existing
downstream pages consume the same canonical APIs after backend normalization.

## Expected Outcome

After this slice:

- a newly connected Bybit integration can backfill directly into a provider
  raw + extracted staging path
- canonical Bybit normalization no longer depends solely on preloaded
  `external_ledger_raw`
- old Mongo data remains preserved while new sessions already run on the new
  acquisition path
