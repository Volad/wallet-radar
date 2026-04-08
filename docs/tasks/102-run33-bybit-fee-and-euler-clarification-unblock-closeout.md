# Run 33 — Bybit Fee and Euler Clarification Unblock Closeout

## Goal

Unblock downstream accounting replay after normalization by fixing two residual
review regressions:

- Bybit standalone fee rows that were incorrectly entering the active
  basis-relevant lane
- audited Euler `batch(...)` rows that already had persisted receipt logs but
  could not re-enter receipt clarification to recover transfer evidence

## Problem

The session reached:

- `BYBIT_NORMALIZATION`
- `PRICING`
- `ACCOUNTING_REPLAY`

but replay stopped in:

- `ACCOUNTING_REPLAY / BLOCKED`

because `blockingNeedsReview = 16`.

The blocker split was:

- `13` Bybit rows with `BYBIT_CANONICAL_TYPE_UNMAPPED`
- `3` Avalanche Euler rows with `CLASSIFICATION_FAILED`

The two families were different regressions.

### Bybit fee regression

The affected rows were not truly unknown:

- `TRANSACTION_LOG / BONUS_RECOLLECT`
- `FUNDING_HISTORY / Loans / Repay Interest`

The extraction layer already mapped them to canonical `FEE`, but still marked
them `basisRelevant = true`. That pushed them into the active Bybit
normalization lane where the canonical builder has no standalone `FEE`
transaction type. The rows then degraded into:

- `UNKNOWN`
- `NEEDS_REVIEW`
- `BYBIT_CANONICAL_TYPE_UNMAPPED`

Historical baseline in `external_ledger_raw.ndjson` already treated these rows
as:

- `canonicalType = FEE`
- `basisRelevant = false`

### Euler clarification regression

The three Avalanche rows had:

- persisted receipt logs
- proved Euler router `batch(...)`
- proved borrow evidence

but:

- `clarificationEvidence.transfers.tokenTransfers = []`

The clarification pipeline had therefore persisted only receipt logs without
transfer projection. Current Euler classification depends on wallet-visible
transfer evidence, so the rows remained:

- `UNKNOWN`
- `NEEDS_REVIEW`
- `CLASSIFICATION_FAILED`

Because receipt logs already existed, the clarification gate incorrectly
treated them as fully clarified and excluded them from another transfer-evidence
recovery attempt.

## Target Policy

1. Standalone Bybit venue-fee rows such as `BONUS_RECOLLECT` and funding-side
   `Repay Interest` remain canonical `FEE`, but they are not basis-opening
   rows.
2. Such rows must not enter the active Bybit basis-relevant normalization lane.
3. Audited Euler `batch(...)` rows are not "fully clarified" when only receipt
   logs were persisted and transfer evidence is still empty.
4. Receipt-only Euler rows with `CLASSIFICATION_FAILED` or
   `EULER_BATCH_DECODER_REQUIRED` remain eligible for receipt clarification
   retry until wallet-visible transfer evidence is recovered or attempts are
   exhausted.

## Scope

In scope:

- demote standalone Bybit `FEE` rows out of the basis-relevant extracted lane
- keep receipt-only Euler `batch(...)` rows retryable for transfer-evidence
  clarification
- make integration `sync_status` lookup duplicate-tolerant so rerun preparation
  cannot be blocked by stale duplicate integration status rows
- add regression tests for both behaviors
- prepare current Mongo data so the next normalization / clarification pass can
  proceed without a full backfill rerun

Out of scope:

- redefining Bybit fee accounting into standalone replay lots
- deleting historical Mongo data
- rewriting the Euler semantic classifier

## Acceptance Criteria

1. `BybitExtractionService` marks standalone `FEE` rows as
   `basisRelevant = false`.
2. `BONUS_RECOLLECT` and funding-history `Repay Interest` no longer enter the
   pending Bybit normalization query.
3. `ReceiptClarificationGateway.fromPersistedEvidence(..., true)` treats
   receipt-only persisted evidence as insufficient for transfer clarification.
4. `ReceiptClarificationEligibilitySupport` keeps Euler `batch(...)` review rows
   retryable when receipt logs exist but transfer evidence is still empty.
5. `PendingReceiptClarificationQueryService` does not exclude those Euler rows
   from the next receipt-clarification batch.
6. Integration `sync_status` consumers select the latest row by
   `integrationId` and do not fail on stale duplicates.
7. Targeted tests cover:
   - Bybit `FEE` demotion
   - receipt-only clarification evidence handling
   - Euler clarification retry eligibility
   - duplicate integration sync-status self-healing
8. Mongo can be prepared for a downstream rerun without re-running Bybit
   backfill from scratch.

## Tasks

### BA-102-01 Requirements

1. Freeze the policy that standalone venue fees are real outflows but not
   standalone basis-opening acquisitions or disposals.
2. Freeze the rule that receipt-only Euler clarification is incomplete when the
   classifier still requires transfer evidence.

### SA-102-02 Architecture

1. Keep current pipeline order unchanged.
2. Preserve the split:
   - external provider enrichment in backfill
   - on-chain clarification for receipt-closeable on-chain rows
3. Clarification retry eligibility must depend on the persisted evidence
   contract, not only on the presence of any receipt logs.

### BE-102-03 Backend

1. Demote standalone Bybit `FEE` rows out of the basis-relevant extracted lane.
2. Treat receipt-only persisted evidence as insufficient for transfer
   clarification reuse.
3. Re-open receipt clarification for Euler `batch(...)` rows that still lack
   transfer evidence.
4. Make integration `sync_status` lookup resilient to duplicate rows and heal
   stale duplicates opportunistically.
5. Add targeted regression tests.
6. Prepare current Mongo rows for the next normalization / clarification pass.

### FE-102-04 Frontend

No frontend work is required in this slice.

## Expected Outcome

After this slice:

- standalone Bybit fee rows stop blocking replay as unmapped active basis rows
- audited Euler Avalanche rows become eligible for one more clarification pass
  that can recover token-transfer evidence from persisted receipt logs
- a downstream rerun can proceed from current raw data without another full
  Bybit backfill
