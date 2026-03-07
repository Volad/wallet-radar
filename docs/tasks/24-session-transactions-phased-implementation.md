# 24 — Session Transactions Phased Implementation

## Task IDs

- **T-052** — Phase 1: SessionTransaction foundation + projection/read API
- **T-053** — Phase 2: Bridge pairing lifecycle (`BRIDGE_OUT/BRIDGE_IN/MATCHED/REVIEW`)
- **T-054** — Phase 3: Session-level AVCO replay and realised PnL totals
- **T-055** — Phase 4: Manual/override SessionTransaction support
- **T-056** — Phase 5: Retry-safety and idempotency hardening only

---

## Worker Handoff Commands

```text
$backend-dev implement docs/tasks/24-session-transactions-phased-implementation.md (T-052) end-to-end with tests
$frontend-dev implement docs/tasks/24-session-transactions-phased-implementation.md (T-052) end-to-end with tests
```

---

## Product Decisions (Locked)

1. Session-scoped transaction storage is required as a separate collection from `normalized_transactions`.
2. Canonical bridge statuses at session layer:
   - `BRIDGE_OUT`
   - `BRIDGE_IN`
   - `MATCHED`
   - `REVIEW`
3. Do not persist per-leg AVCO/PnL in `SessionTransaction` by default.
4. Persist at transaction level:
   - `realisedPnlUsdTotal`
   - `avcoSnapshotVersion`
5. Phase 5 scope is strictly limited to retry/idempotency hardening.

---

## Phase 1 — T-052 (Implement Now)

### Goal

Introduce a production-safe SessionTransaction foundation and first read path without changing existing normalized ingestion contract.

### Backend scope

1. Add `SessionTransaction` domain model + repository:
   - collection: `session_transactions`
   - required fields:
     - `sessionId`
     - source lineage (`sourceType`, `sourceId`)
     - canonical tx identity (`txHash`, `networkId`, `walletAddress`, `blockTimestamp`, `type`)
     - deterministic replay ordering key (`sortKey`)
     - `flows[]` (movement facts only)
     - `realisedPnlUsdTotal`
     - `avcoSnapshotVersion`
     - bridge placeholders (`bridgeStatus`, `bridgePairKey`) for later phases
     - audit timestamps (`createdAt`, `updatedAt`)
2. Add indexes for:
   - uniqueness of session+source lineage
   - session timeline reads (`sessionId + blockTimestamp + sortKey`)
3. Add deterministic sort-key contract implementation (AUD-202 baseline) for chain-sourced rows.
4. Add projection command:
   - rebuild session rows from `normalized_transactions` (`status=CONFIRMED`) for wallets in `UserSession`
   - idempotent replace for CHAIN-sourced rows in that session
5. Add API endpoints:
   - `POST /api/v1/sessions/{sessionId}/transactions/rebuild` (`202`)
   - `GET /api/v1/sessions/{sessionId}/transactions?limit={n}` (`200`)
6. Add tests:
   - integration tests for both endpoints
   - unit tests for sort-key determinism and mapping invariants

### Frontend scope

1. Add strict DTOs for Phase 1 session-transactions responses.
2. Extend `WalletApiService` with typed methods:
   - `rebuildSessionTransactions(sessionId)`
   - `getSessionTransactions(sessionId, limit?)`
3. Add API service unit tests for the new methods.

### DoD (Phase 1)

1. SessionTransaction collection is created and populated by rebuild endpoint.
2. GET endpoint returns typed, deterministic timeline rows from `session_transactions`.
3. Existing `/sessions` and `/wallets` contracts remain backward-compatible.
4. Backend and frontend tests for T-052 pass.

---

## Phase 2 — T-053

### Goal

Implement bridge lifecycle matching across session wallets/networks.

### Backend tasks

1. Implement matching policy for bridge legs across networks.
2. Lifecycle transitions:
   - `BRIDGE_OUT` or `BRIDGE_IN` -> `MATCHED` or `REVIEW`
3. Add safe reconciliation for late-arriving opposite leg.
4. Add deterministic re-evaluation rules (no double matching).
5. Add tests for:
   - ordered/late/out-of-order bridge legs
   - conflict resolution and review fallback

### Frontend tasks

1. Add bridge status rendering and filtering in transaction list.
2. Add explicit `REVIEW` badges and UX copy for unmatched bridges.
3. Add component tests for status transitions rendering.

### DoD

- Bridge statuses are consistent and deterministic for repeated rebuilds.

---

## Phase 3 — T-054

### Goal

Compute accounting at session level from SessionTransactions.

### Backend tasks

1. Add session replay engine over session timeline ordering contract.
2. Persist:
   - `realisedPnlUsdTotal`
   - `avcoSnapshotVersion`
3. Keep per-leg AVCO/PnL out of SessionTransaction unless strict necessity emerges.
4. Add replay idempotency tests and invariant coverage.

### Frontend tasks

1. Wire session-level PnL/AVCO summary from new session endpoints.
2. Keep backward-compatible UI fallback while migration is in progress.

### DoD

- Session accounting replay is deterministic and repeatable.

---

## Phase 4 — T-055

### Goal

Support manual and override rows at session layer with full normalized-like payload.

### Backend tasks

1. Add manual SessionTransaction creation/update with source lineage.
2. Add override/reverse lifecycle semantics for session rows.
3. Rebuild/replay interoperability with chain rows.
4. Add validation and conflict-handling tests.

### Frontend tasks

1. Add UI workflows for adding/editing compensating session transactions.
2. Add form validation + optimistic UX states + rollback on API error.

### DoD

- Manual/override rows are first-class and replay-safe.

---

## Phase 5 — T-056

### Goal

Finalize retry/idempotency guarantees for session transaction pipeline.

### Backend tasks

1. Ensure idempotent command semantics for rebuild/replay/manual mutation endpoints.
2. Add retry-safe persistence strategy for session rows.
3. Add regression tests for repeated requests and partial-failure recovery.

### Frontend tasks

1. Ensure safe client retries for rebuild/manual requests.
2. Add duplicate-submit protection around mutation actions.

### DoD

- Repeated operations are safe and do not produce duplicate or divergent session state.
