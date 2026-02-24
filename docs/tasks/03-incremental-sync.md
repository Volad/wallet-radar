# Feature 3: Incremental sync (hourly + manual refresh)

**Tasks:** T-010, T-023 (POST /sync/refresh)

---

## T-010 — Incremental sync job

- **Module(s):** ingestion (job)
- **Description:** Implement `IncrementalSyncJob`: `@Scheduled(fixedDelay=3_600_000)`. For each wallet×network with backfill complete, fetch from lastBlockSynced+1 to current block; same pipeline as backfill (classify → normalize → price → store → AVCO recalc). Update lastBlockSynced. Use `sync-executor` for parallelism.
- **Doc refs:** 02-architecture (Data Flow 2 — Incremental Sync)
- **DoD:** Scheduled job; unit tests; integration test: two runs, second run only new blocks.
- **Dependencies:** T-009 (pipeline), T-015

## T-023 (partial) — POST /sync/refresh

- **Module(s):** api
- **Description:** Implement `POST /api/v1/sync/refresh` (body: wallets, networks): trigger incremental sync for the given set; return 202 with message. (Full T-023 in 01-add-wallet-backfill.md.)
- **Doc refs:** 04-api (Trigger Manual Sync)
- **DoD:** Controller triggers sync; integration test: POST → 202; sync runs for given wallets/networks.

---

## Acceptance criteria

- A scheduled job runs periodically (e.g. hourly) for wallet×network pairs where backfill is complete; it fetches only new blocks since `lastBlockSynced`.
- `POST /api/v1/sync/refresh` with `{ wallets, networks }` triggers the same incremental sync for the given set; response is `202` with a clear message.
- New transactions are ingested with the same idempotency rule (`txHash + networkId`); no duplicate events.
- After incremental sync, `lastBlockSynced` (or equivalent) is updated and AVCO/positions are updated for affected assets.
- GET endpoints (e.g. GET /assets) perform **zero RPC calls** during the request.

## User-facing outcomes

- Portfolio stays up to date after the first backfill; user can trigger a refresh without re-adding the wallet.

## Edge cases / tests

- No new blocks since last sync → no new events; `lastBlockSynced` unchanged or updated to current; no errors.
- Same tx appears in two sync runs → idempotent; single event in DB.
