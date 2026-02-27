# Review: ADR-025 Normalized Transaction Pipeline Acceptance

**Date:** 2026-02-27  
**Participants:** Business Analyst, System Architect  
**Decision:** Accepted for implementation

---

## Scope accepted

- Canonical transaction layer is `normalized_transactions`.
- Operation statuses are explicit: `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED` (+ `NEEDS_REVIEW`).
- `SWAP` is represented as operation with canonical `legs[]`.
- UI transaction history shows `CONFIRMED` by default.
- Additional clarification RPC is selective: only for `PENDING_CLARIFICATION`.
- Existing `economic_events` state is not required to be preserved for rollout continuity.

## BA acceptance highlights

- No transaction is silently dropped; unresolved ambiguity ends in `NEEDS_REVIEW`.
- A swap is `CONFIRMED` only when both inbound and outbound legs are complete and consistent.
- Pending/non-confirmed records are excluded from default user-facing history.

## Architecture constraints

- Keep `raw_transactions` immutable.
- Maintain idempotency key `(txHash, networkId, walletAddress)` for normalized operations.
- Preserve snapshot-first read path (no RPC in GET handlers).
- AVCO replay input must be deterministic and based on confirmed canonical legs.

## Worker command

```text
$worker implement docs/tasks/15-normalized-transactions-pipeline.md (T-034..T-038) end-to-end with tests
```
