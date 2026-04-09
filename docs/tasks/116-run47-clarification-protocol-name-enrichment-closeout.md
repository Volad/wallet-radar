# 116 - Clarification-Time Protocol Name Enrichment

Status: Implemented

Owner: Codex

Date: 2026-04-08

## Problem

Canonical `normalized_transactions` already carried correct economic `type`
for many swaps / bridges / vault / lending rows, but `protocolName` was often
missing even when persisted raw or clarification evidence clearly pointed to a
known protocol contract.

This created two product problems:

- debug and filtering views lost useful protocol context
- registry growth required either a full normalization rerun or ad-hoc UI
  decoration, neither of which matched the intended pipeline contract

## Decision

Treat `protocolName` as clarification-time canonical enrichment, not as a UI
label and not as a blocker for economic normalization.

Approved pipeline:

1. classification
2. clarification
   - receipt enrichment
   - metadata enrichment
   - lifecycle linking
   - protocol detection / `protocolName` enrichment
3. optional reclassification
4. pricing
5. accounting replay

Rules:

- `protocolName` / `protocolVersion` may be written during normalization only
  on direct high-confidence registry hits
- clarification may fill missing `protocolName` / `protocolVersion` from
  persisted raw and clarification evidence
- clarification-time protocol enrichment must never mutate `type`, `status`, or
  canonical flows by itself
- reclassification remains optional and is triggered only when clarification
  changes economic facts, not merely protocol identity
- registry expansion must be deployable independently of normalization and must
  support backfilling old canonical rows

## Implemented Scope

### Clarification-time enrichment

- Added `ProtocolNameResolutionService`
- Added `ProtocolNameEnrichmentService`
- Added clarification-batch drain after bridge reconciliation in
  `OnChainClarificationJob`
- Added post-save protocol enrichment hook in
  `ClarificationReclassificationHandler`

### Historical repair

- Added one-shot `ProtocolNameRepairJob` on application startup
- The repair scans already-normalized on-chain rows with empty
  `protocolName` and attempts deterministic enrichment from persisted raw
  evidence
- No full normalization rerun is required when only protocol identity coverage
  changes

### Registry-first extensibility

First-wave registry expansion was added for high-confidence entrypoints:

- Uniswap Universal Router
- LI.FI Diamond additional network coverage
- ParaSwap / Velora Augustus V6.2
- Aave Wrapped Token Gateway V3

This model is intentionally open-ended: future protocol additions such as
Yearn, SushiSwap, Lagoon, Balancer, CoW Swap, Euler, Morpho, Fluid, Merkl, and
similar families can be shipped as registry growth plus repair/backfill without
changing the pipeline contract.

## Acceptance Criteria

- Missing `protocolName` is not a clarification blocker by itself
- Clarification may persist `protocolName` / `protocolVersion` on canonical
  rows when deterministic registry evidence exists
- Historical rows can be enriched after deploy without full normalization rerun
- Economic fields remain unchanged by protocol-only enrichment:
  - `type`
  - `status`
  - `flows`
  - pricing flags
  - replay fields
- Protocol registry growth remains append-only and operationally safe

## Follow-ups

1. Expand registry coverage for factory-derived / helper-heavy protocols:
   - Yearn
   - SushiSwap
   - Lagoon
   - Balancer
   - CoW Swap
   - Euler
   - Morpho
   - Fluid
   - Merkl
2. Add API/UI protocol filters backed by canonical `protocolName`
3. Introduce provenance metadata later if the product needs to distinguish
   direct registry hits from clarification-time inherited labels
