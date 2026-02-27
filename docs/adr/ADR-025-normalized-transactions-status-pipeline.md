# ADR-025: Status-Driven `normalized_transactions` Pipeline with Canonical `legs[]`

**Date:** 2026-02-27  
**Status:** Accepted  
**Deciders:** Product Owner, Business Analyst, System Architect

---

## Context

Current ingestion/accounting flow writes `economic_events` directly from classifier output.
For complex swaps (especially native-leg payouts after unwrap/internal calls), one leg can be missing at first pass.
This causes partial swap representation (`SWAP_SELL` without `SWAP_BUY`) and ambiguous UX.

The product requirement is to keep a clean, explicit processing pipeline where transactions move through
clarification, pricing, and finalization states, and users see only finalized records.

## Decision

Introduce a new canonical operation layer: **`normalized_transactions`**.

Pipeline:
1. Keep `raw_transactions` ingestion as-is (receipt-first).
2. Classify into `normalized_transactions` by transaction operation type.
3. Store canonical movement legs in `normalized_transactions.legs[]`.
4. Process status transitions:
   - `PENDING_CLARIFICATION`
   - `PENDING_PRICE`
   - `PENDING_STAT`
   - `CONFIRMED`
   - `NEEDS_REVIEW` (non-recoverable ambiguity)
5. UI/API transaction history returns only `CONFIRMED` by default.

`SWAP` representation:
- `type = SWAP`
- `legs[]` contains at least one SELL leg and one BUY leg for confirmed swap
- Optional UI aliases: `leftLeg/rightLeg` fields may be derived from `legs[]`

### Clarification rule (cost control)

Additional RPC enrichment (e.g., traces/internal-native resolution) is executed **only** for
`PENDING_CLARIFICATION` records. No global extra RPC calls for already complete transactions.

## Data model

Collection: `normalized_transactions`

Required fields:
- `txHash`, `networkId`, `walletAddress`, `blockTimestamp`
- `type` (SWAP, INTERNAL_TRANSFER, STAKE_DEPOSIT, STAKE_WITHDRAWAL, LP_ENTRY, LP_EXIT, LEND_DEPOSIT, LEND_WITHDRAWAL, BORROW, REPAY, EXTERNAL_TRANSFER_OUT, EXTERNAL_INBOUND, MANUAL_COMPENSATING)
- `status`
- `legs[]`:
  - `role` (`BUY|SELL|FEE|TRANSFER`)
  - `assetContract`, `assetSymbol`
  - `quantityDelta` (signed)
  - `unitPriceUsd?`, `valueUsd?`, `priceSource?`
  - `isInferred` (boolean)
  - `inferenceReason?`, `confidence?`
- `missingDataReasons[]`
- `createdAt`, `updatedAt`, `confirmedAt?`

Indexes:
- unique `(txHash, networkId, walletAddress)`
- `(walletAddress, networkId, status, blockTimestamp)`
- multikey on `legs.assetContract`

## Accounting impact

AVCO/P&L input source becomes `normalized_transactions` where `status=CONFIRMED` and legs are complete.
The accounting engine computes per-asset timeline from `legs[]` in chronological order.

`economic_events` is no longer required as source of truth for new pipeline.
Legacy data may be ignored/archived based on rollout plan.

## Consequences

Positive:
- Clear deterministic workflow and observability by status.
- Partial swaps are explicitly tracked and resolved.
- Native-leg edge cases become recoverable through targeted clarification.
- UI consistency: only final records are shown.

Trade-offs:
- Additional storage and workflow complexity.
- New jobs and status transitions to maintain.

## Existing data strategy

For this initiative, existing `economic_events` state is non-blocking.
Recommended rollout:
1. Keep backup (safety only).
2. Start writing/serving from `normalized_transactions` pipeline.
3. Rebuild derived aggregates (`asset_positions`, snapshots) from confirmed legs.
4. Archive or drop legacy `economic_events` once validated.
