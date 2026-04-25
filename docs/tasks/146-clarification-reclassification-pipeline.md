# Task 146: Clarification Evidence Stage and Reclassification Queue

Status: Implemented
Owner: backend-dev
Architectural basis: ADR-002

## Objective

Refactor on-chain clarification so it only fetches and persists bounded evidence. All canonical economic decisions after clarification must be made by the normal `OnChainClassifier` through an explicit `PENDING_RECLASSIFICATION` queue.

## Scope

- Add `PENDING_RECLASSIFICATION` to `NormalizedTransactionStatus`.
- Add `ON_CHAIN_RECLASSIFICATION` to session pipeline stages.
- Change metadata and full-receipt clarification workflows:
  - fetch evidence
  - persist `raw_transactions.clarificationEvidence`
  - update attempt counters
  - mark normalized row `PENDING_RECLASSIFICATION`
  - do not call `OnChainClassifier` inside clarification workflows
- Add an event-driven `OnChainReclassificationJob`.
- Add a reclassification service/query path that:
  - loads `PENDING_RECLASSIFICATION` rows in deterministic order
  - loads matching `RawTransaction`
  - runs the existing `OnChainClassifier`
  - rebuilds canonical normalized rows with `OnChainNormalizedTransactionBuilder.rebuildAfterReclassification`
  - applies allowed metadata enrichment after reclassification
- Update gates and watchdogs:
  - linking blocks on `PENDING_CLARIFICATION` and `PENDING_RECLASSIFICATION`
  - pricing/AVCO readiness blocks on both queues
  - resume watchdog emits reclassification when rows are pending
- Default clarification concurrency:
  - `threads = 2`
  - `batchSize = 30`
  - both configurable under `walletradar.normalization.clarification`
- Add claim/lease protection for concurrent clarification workers.

## Acceptance Criteria

1. `OnChainClarificationJob` no longer directly drives linking.
2. Clarification success writes evidence and moves the row to `PENDING_RECLASSIFICATION`.
3. Reclassification is the only post-clarification path that calls `OnChainClassifier`.
4. Linking and pricing do not start while either queue is non-empty:
   - `PENDING_CLARIFICATION`
   - `PENDING_RECLASSIFICATION`
5. The resume watchdog can recover a stalled session with pending reclassification rows.
6. Focused backend tests cover:
   - clarification marks reclassification instead of rebuilding canonical semantics
   - reclassification rebuilds canonical rows from persisted evidence
   - linking gate blocks on pending reclassification
   - pricing readiness blocks on pending reclassification
   - session resume publishes reclassification request

## Operational Notes

- Do not reset, truncate, or rewrite MongoDB data as part of implementation.
- Existing data can be rerun by the operator after deployment.
- No one-off repair or startup sweep should be introduced as the correctness path.

## Implementation Notes

- Implemented `PENDING_RECLASSIFICATION` and `ON_CHAIN_RECLASSIFICATION`.
- Clarification now claims leased batches and marks resolved evidence rows for reclassification instead of rebuilding canonical semantics inline.
- Reclassification listens after clarification, runs the normal `OnChainClassifier`, then hands off to linking gates.
- Linking, pricing, and AVCO gates now block on pending reclassification rows.
