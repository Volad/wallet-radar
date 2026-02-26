# Feature 4: Current balance (poll + initial refresh on wallet add + manual refresh)

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

## T-014 — BalanceController POST /wallets/balances/refresh + shared refresh service

- **Module(s):** api
- **Description:** Implement `POST /api/v1/wallets/balances/refresh`: body `{ wallets, networks }`. Trigger the same fetch as CurrentBalancePollJob for the given set (async or sync); return 202 and message. Extract common refresh logic into one service reused by: (a) `CurrentBalancePollJob`, (b) manual refresh endpoint, (c) initial refresh triggered after `POST /api/v1/wallets`.
- **Doc refs:** 04-api (Trigger Manual Balance Refresh), 02-architecture (Manual refresh)
- **DoD:** Controller; integration test: POST then GET /assets shows updated onChainQuantity when implemented in assets response.
- **Dependencies:** T-012, T-013, T-023 (wallet add flow)

---

## Acceptance criteria

- A scheduled job runs on a fixed interval (e.g. every 10 minutes) and, for each known (wallet, network), fetches current on-chain balance (native + configured tokens) via RPC and upserts into `on_chain_balances` keyed by (walletAddress, networkId, assetContract).
- On successful `POST /api/v1/wallets`, the system triggers an **asynchronous initial balance refresh** for the same wallet+network set; API response remains `202` and is not blocked by RPC calls.
- `POST /api/v1/wallets/balances/refresh` with `{ wallets, networks }` triggers the same fetch for the given set; response `202`; no jobId required (balances appear on next GET /assets or balances).
- Stored fields include at least `quantity` and `capturedAt` (timestamp of the poll).
- Balance poll is **independent of backfill** (runs even if backfill is not complete).
- Failure of initial/manual refresh for one wallet×network must not fail backfill or other wallet×network refreshes (partial failure handling).

## User-facing outcomes

- User sees current on-chain balances shortly after adding a wallet and can trigger an immediate refresh; reconciliation can compare these to derived quantity.

## Edge cases / tests

- Wallet has no tokens on-chain → stored quantity 0 (or no row) per asset; no crash.
- RPC temporarily fails → behaviour defined (retry / partial update / next run); test that one failure does not corrupt existing balance rows.
- New wallet just added: initial async refresh may succeed before backfill completes; reconciliation may still be NOT_APPLICABLE until first derived position appears.
- Some networks succeed and others fail during initial refresh for a multi-network add → successful networks get fresh balances; failed ones are retried and do not block the rest.
