# Feature 22: Session Controller and Session-Level Backfill Progress

**Tasks:** T-050

---

## Worker Handoff Command

Use worker skill for this feature implementation:

```text
$worker implement docs/tasks/22-session-controller-and-backfill-status.md (T-050) end-to-end with tests
```

---

## T-050 — Introduce session model and `/sessions` API without changing existing `/wallets` contract

- **Module(s):** `api/controller`, `api/dto`, `api/validation`, `domain/session`, `ingestion/wallet/command`, `ingestion/wallet/query`, `frontend`
- **Roles:** business-analyst + system-architect requirements implemented by worker

### Product decisions (locked)

1. Existing `WalletController` endpoints stay unchanged and continue to work as-is.
2. Add new `SessionController` endpoint:
   - `POST /api/v1/sessions`
   - request/response DTOs must be named `AddSessionRequest` / `AddSessionResponse`.
3. Add read endpoints:
   - `GET /api/v1/sessions/{sessionId}` (returns persisted session wallets/settings),
   - `GET /api/v1/sessions/{sessionId}/backfill-status` (session-level progress polling endpoint).
4. Repeated `POST /api/v1/sessions` with the same `sessionId` uses **replace semantics** (full replacement of wallets/settings in that session).
5. Validation is **all-or-nothing**: if any wallet in payload is invalid, return `400` and persist/start nothing.
6. Session persistence has **no TTL** (stored indefinitely).
7. Supported networks for this flow are fixed EVM set:
   - `ETHEREUM`, `ARBITRUM`, `OPTIMISM`, `POLYGON`, `BASE`, `BSC`, `AVALANCHE`, `MANTLE`, `LINEA`, `UNICHAIN`, `ZKSYNC`.

### API contract (new)

#### `POST /api/v1/sessions`

Request (`AddSessionRequest`):

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "wallets": [
    {
      "address": "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
      "label": "Wallet 1",
      "color": "#22d3ee",
      "networks": [
        "ETHEREUM",
        "ARBITRUM",
        "OPTIMISM",
        "POLYGON",
        "BASE",
        "BSC",
        "AVALANCHE",
        "MANTLE",
        "LINEA",
        "UNICHAIN",
        "ZKSYNC"
      ]
    }
  ]
}
```

Behavior:

- Validate full payload first (`sessionId`, each wallet address, label, color, networks).
- Replace existing stored session by `sessionId` (if exists).
- For each wallet and each selected network, trigger existing backfill flow (`sync_status` + backfill queue) exactly as current implementation does for wallet add.
- Non-blocking behavior (`202 Accepted`) is required.

Response (`AddSessionResponse`, `202`):

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "message": "Session saved, backfill started"
}
```

#### `GET /api/v1/sessions/{sessionId}`

Response (`200`):

```json
{
  "sessionId": "549b0aba-a9af-4789-b125-ebb86314a3f1",
  "wallets": [
    {
      "address": "0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f",
      "label": "Wallet 1",
      "color": "#22d3ee",
      "networks": [
        "ETHEREUM",
        "ARBITRUM",
        "OPTIMISM",
        "POLYGON",
        "BASE",
        "BSC",
        "AVALANCHE",
        "MANTLE",
        "LINEA",
        "UNICHAIN",
        "ZKSYNC"
      ]
    }
  ]
}
```

- `404` if session not found.

#### `GET /api/v1/sessions/{sessionId}/backfill-status`

Response (`200`) must provide:

- session-level aggregate progress (`overallProgressPct`),
- aggregate status (`PENDING` | `RUNNING` | `COMPLETE` | `PARTIAL` | `FAILED`),
- per wallet+network status details sourced from `sync_status`.

`404` if session not found.

### Domain model requirements

Add new persisted model `UserSession` (collection name `user_sessions`) with at least:

- `id` (`sessionId`, unique),
- `wallets[]` with:
  - `address` (canonical normalized form used for matching),
  - `label`,
  - `color`,
  - `networks[]`,
- `createdAt`,
- `updatedAt`,
- optional `lastSeenAt`.

No TTL index.

### Backfill aggregation rules for `GET /backfill-status`

1. Build the full target set from session wallets and their selected networks.
2. Lookup `sync_status` for each `(walletAddress, networkId)`.
3. If a pair has no `sync_status` row yet, treat it as `PENDING` with `progressPct = 0`.
4. Aggregate progress as arithmetic mean across all target pairs.
5. Aggregate state:
   - `COMPLETE` when all targets are complete,
   - `RUNNING` when any target is `PENDING`/`RUNNING`,
   - `FAILED` when no running targets and at least one target is `FAILED`/`ABANDONED`,
   - otherwise `PARTIAL`.

### Worker implementation scope

1. **API + DTO + validation**
   - Add `SessionController` with three endpoints above.
   - Add `AddSessionRequest`, `AddSessionResponse`, session read/status response DTOs.
   - Add validators for:
     - `sessionId` UUID format,
     - EVM wallet address format only (`0x` + 40 hex),
     - fixed EVM network enum values only,
     - wallet `label` non-blank,
     - wallet `color` hex format (`#RRGGBB`).

2. **Session persistence**
   - Add `UserSession` model + repository.
   - Implement replace semantics for `POST /sessions`.

3. **Backfill trigger integration**
   - Reuse existing wallet backfill command service for each wallet+network set.
   - Do not alter existing `/api/v1/wallets` controller contract.

4. **Session query endpoints**
   - Implement session fetch endpoint.
   - Implement session-level backfill aggregation endpoint.

5. **Frontend integration**
   - Switch wallet-add flow to `POST /api/v1/sessions`.
   - Use returned `sessionId` for polling `GET /api/v1/sessions/{sessionId}/backfill-status`.
   - Keep progress bar hidden until `POST /sessions` succeeds.

### Required tests

Backend:

- `SessionController` integration tests:
  - valid `POST /sessions` -> `202`;
  - same `sessionId` second post replaces previous session wallets/settings;
  - invalid one wallet in payload -> `400`, and no partial write/no backfill start;
  - `GET /sessions/{id}` -> `200` or `404`;
  - `GET /sessions/{id}/backfill-status` -> `200` with expected aggregation or `404`.

- Service/repository unit tests:
  - replace semantics,
  - aggregation rules from `sync_status`,
  - missing `sync_status` pair treated as `PENDING/0`.

Frontend:

- API service test for new `/sessions` endpoint.
- Component tests for submit enablement + progress visibility behavior.

### Acceptance Criteria (DoD)

- New session API is available and covered by tests.
- Existing wallet API remains backward-compatible and untouched.
- Session payload supports multiple wallets with `address`, `label`, `color`, `networks`.
- One invalid wallet invalidates the whole request.
- Session replace semantics work deterministically by `sessionId`.
- Session progress endpoint supports polling every few seconds and returns stable aggregate progress/state.
- Session data is persisted without TTL expiration.
