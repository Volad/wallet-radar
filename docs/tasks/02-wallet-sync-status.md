# Feature 2: Wallet sync status

**Tasks:** T-011, T-023 (GET /wallets/{address}/status)

---

## T-011 — Sync status and progress tracker

- **Module(s):** ingestion (job), domain
- **Description:** Maintain `sync_status` per (walletAddress, networkId): status (PENDING/RUNNING/COMPLETE/PARTIAL/FAILED), progressPct, lastBlockSynced, syncBannerMessage, backfillComplete. `SyncProgressTracker` updates progress during backfill/sync. API will read this for GET /wallets/{address}/status.
- **Doc refs:** 02-architecture (sync_status), 04-api (Get Wallet Sync Status)
- **DoD:** Persist sync_status; tracker updates; unit tests; integration test: backfill updates progress and status.
- **Dependencies:** T-002, T-009

## T-023 (partial) — GET /wallets/{address}/status

- **Module(s):** api
- **Description:** Implement `GET /api/v1/wallets/{address}/status?network=`: return sync status, progressPct, syncBannerMessage, backfillComplete, lastBlockSynced. (Full T-023 also includes POST /wallets and POST /sync/refresh; see 01-add-wallet-backfill.md and 03-incremental-sync.md.)
- **Doc refs:** 04-api (Get Wallet Sync Status)
- **DoD:** Controller returns correct DTO; integration test: GET returns 200 with expected fields; 404 when wallet not found.

---

## Acceptance criteria

- `GET /api/v1/wallets/{address}/status?network={networkId}` returns `200` with `walletAddress`, `networkId`, `status`, `progressPct`, `lastBlockSynced`, `backfillComplete`, `syncBannerMessage`.
- `status` is one of `PENDING` | `RUNNING` | `COMPLETE` | `PARTIAL` | `FAILED`.
- When backfill is complete for that wallet×network, `syncBannerMessage` is `null`.
- If no sync status exists for the given address (and optionally network), return `404` with `WALLET_NOT_FOUND`.

## User-facing outcomes

- User sees current sync state and progress for each added wallet×network and knows when backfill is done.

## Edge cases / tests

- Wallet never added → 404.
- Status requested without `network` (if API supports) → behaviour defined (e.g. all networks or 400); document and test.
