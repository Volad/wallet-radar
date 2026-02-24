# Feature 4: Current balance poll (cron + manual refresh)

**Tasks:** T-012, T-013, T-014

---

## T-012 — OnChainBalanceStore

- **Module(s):** ingestion (store)
- **Description:** Implement `OnChainBalanceStore`: upsert by (walletAddress, networkId, assetContract) UNIQUE; store quantity and capturedAt. Support native (e.g. assetContract = zero or sentinel). Used by balance poll and manual refresh.
- **Doc refs:** 02-architecture (on_chain_balances, store), 01-domain (On-Chain Balance)
- **DoD:** Store implementation; integration test: upsert and read by wallet+network+asset.
- **Dependencies:** T-002

## T-013 — CurrentBalancePollJob

- **Module(s):** ingestion (job)
- **Description:** Implement `CurrentBalancePollJob`: `@Scheduled(fixedRate=600_000)` (10 min). For each (wallet, network) from sync_status, call RPC for native + token balances (from known assets for that wallet or config). Write to `OnChainBalanceStore.upsert`. No dependency on backfill completion.
- **Doc refs:** 02-architecture (Data Flow 2b, scheduler-pool), ADR-007
- **DoD:** Scheduled job; unit tests with mocked RPC and store; integration test optional (real RPC or stub).
- **Dependencies:** T-004, T-005, T-012

## T-014 — BalanceController POST /wallets/balances/refresh

- **Module(s):** api
- **Description:** Implement `POST /api/v1/wallets/balances/refresh`: body `{ wallets, networks }`. Trigger same fetch as CurrentBalancePollJob for the given set (async or sync); return 202 and message. Updated balances visible on next GET /assets.
- **Doc refs:** 04-api (Trigger Manual Balance Refresh), 02-architecture (Manual refresh)
- **DoD:** Controller; integration test: POST then GET /assets shows updated onChainQuantity when implemented in assets response.
- **Dependencies:** T-012, T-013 (or direct call to same service used by job)

---

## Acceptance criteria

- A scheduled job runs on a fixed interval (e.g. every 10 minutes) and, for each known (wallet, network), fetches current on-chain balance (native + configured tokens) via RPC and upserts into `on_chain_balances` keyed by (walletAddress, networkId, assetContract).
- `POST /api/v1/wallets/balances/refresh` with `{ wallets, networks }` triggers the same fetch for the given set; response `202`; no jobId required (balances appear on next GET /assets or balances).
- Stored fields include at least `quantity` and `capturedAt` (timestamp of the poll).
- Balance poll is **independent of backfill** (runs even if backfill is not complete).

## User-facing outcomes

- User sees current on-chain balances and can trigger an immediate refresh; reconciliation can compare these to derived quantity.

## Edge cases / tests

- Wallet has no tokens on-chain → stored quantity 0 (or no row) per asset; no crash.
- RPC temporarily fails → behaviour defined (retry / partial update / next run); test that one failure does not corrupt existing balance rows.
- New wallet just added: balance poll may run before backfill completes; reconciliation may show NOT_APPLICABLE or no on-chain data until first successful poll.
