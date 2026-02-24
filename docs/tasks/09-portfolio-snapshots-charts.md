# Feature 11: Portfolio snapshots and charts (read path)

**Tasks:** T-021, T-022, T-026, T-027

---

## T-021 — SnapshotBuilder

- **Module(s):** snapshot
- **Description:** Implement `SnapshotBuilder`: for given (wallet, network) and snapshotTime (hour-truncated), load asset_positions, resolve spot price per asset via SpotPriceResolver, compute valueUsd and unrealisedPnlUsd per asset; build PortfolioSnapshot (per-wallet only). Idempotent upsert on (walletAddress, networkId, snapshotTime).
- **Doc refs:** 01-domain (PortfolioSnapshot, AssetSnapshot), 02-architecture (SnapshotBuilder), 03-accounting (Unrealised P&L)
- **DoD:** Builder + repository; unit tests; integration test: positions exist → build snapshot → document stored.
- **Dependencies:** T-015, T-020, T-002

## T-022 — SnapshotCronJob and SnapshotAggregationService

- **Module(s):** snapshot
- **Description:** Implement `SnapshotCronJob`: after incremental sync (or on schedule), for each wallet×network run SnapshotBuilder for current hour. Implement `SnapshotAggregationService`: on-request aggregation for GET /portfolio/snapshots: query portfolio_snapshots by wallets + range; merge per-wallet snapshots into time-series (e.g. sum totalValueUsd per bucket); optional crossWalletAvco from CrossWalletAvcoAggregatorService. Use snapshot cache (sorted(wallets)+range, TTL 60min).
- **Doc refs:** 02-architecture (SnapshotCronJob, SnapshotAggregationService, Data Flow 2 and 3)
- **DoD:** Cron job + aggregation service; unit tests; integration test: build snapshots → GET snapshots returns aggregated series.
- **Dependencies:** T-021, T-016

## T-026 — ChartController

- **Module(s):** api
- **Description:** Implement `GET /api/v1/portfolio/snapshots` and `GET /api/v1/charts/asset/{symbol}`: delegate to SnapshotAggregationService; return range, wallets, dataPoints (timestamp, totalValueUsd, etc.) and asset chart (valueUsd, spotPriceUsd, perWalletAvco, unrealisedPnlUsd). No RPC; read from snapshots/cache.
- **Doc refs:** 04-api (Portfolio Snapshots, Get Asset Chart Data)
- **DoD:** Controllers; integration tests: GET returns correct shape and range.
- **Dependencies:** T-022

## T-027 — SnapshotController

- **Module(s):** api
- **Description:** Expose GET portfolio/snapshots under SnapshotController if not under ChartController; align with 04-api (GET /api/v1/portfolio/snapshots).
- **Doc refs:** 04-api (Portfolio Snapshots), 02-architecture (SnapshotController)
- **DoD:** Controller; integration test.
- **Dependencies:** T-022

---

## Acceptance criteria

- `GET /api/v1/portfolio/snapshots?wallets=0xA,0xB&range=7D` returns time-series of portfolio value (e.g. `dataPoints[]` with `timestamp`, `totalValueUsd`, `unrealisedPnlUsd`, `unresolvedCount`) without calling RPC.
- `GET /api/v1/charts/asset/{symbol}?wallets=...&range=...` returns asset-level time-series (e.g. `valueUsd`, `spotPriceUsd`, `perWalletAvco`, `unrealisedPnlUsd`) without calling RPC.
- Data is read from stored `portfolio_snapshots` (and aggregation) and caches; no heavy computation on the request path (INV-10).
- Snapshots are per-wallet (and optionally per network); aggregation for multiple wallets is done from stored snapshots.

## User-facing outcomes

- User sees portfolio and asset charts for 1D/7D/1M/1Y/ALL without slow or RPC-dependent responses.

## Edge cases / tests

- No snapshots for range → empty or sparse series; no 500.
- Single wallet vs multiple wallets → aggregation sums or merges correctly; `crossWalletAvco` for charts follows same rules as GET /assets if exposed.
