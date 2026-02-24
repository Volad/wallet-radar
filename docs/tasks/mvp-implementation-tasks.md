# WalletRadar MVP — Task index and dependency order

**Per-feature files:** Each feature (and each infra block) has its own file under `docs/tasks/` with full task text and acceptance criteria. See **README.md** for the file list.

This file keeps the **dependency order** and a short reference; full task descriptions live in the per-feature files.

**Completed tasks (update after each PR merge):**

| Completed task IDs | Feature file |
|--------------------|--------------|
| T-001 … T-008      | 00-foundation.md, 00-ingestion-core.md |
| T-015, T-016       | 00-cost-basis-engine.md |
| T-017              | 05-override-recalc.md (partial) |
| T-019, T-020       | 00-pricing.md |

**Current step:** T-009, T-023 — `01-add-wallet-backfill.md`

---

## Foundation

**T-001 — Domain model and value objects**
- **Module(s):** domain
- **Description:** Implement core domain types: `EconomicEvent`, `AssetPosition`, `PortfolioSnapshot`, `AssetSnapshot`; enums `EconomicEventType`, `PriceSource`, `FlagCode`, `NetworkId`; value objects used across modules. All monetary fields use `BigDecimal`. No business logic in domain package.
- **Doc refs:** 01-domain (Core Entities, Event Types, Price Sources, Flag Codes), INV-06
- **DoD:** Implement types and enums; unit tests for value object behaviour and invariants (e.g. quantity sign).
- **Dependencies:** —

**T-002 — MongoDB collections and indexes**
- **Module(s):** config (Mongo), domain (repositories)
- **Description:** Define collections: `raw_transactions`, `economic_events`, `asset_positions`, `portfolio_snapshots`, `cost_basis_overrides`, `sync_status`, `recalc_jobs`, `on_chain_balances`. Configure Decimal128 codec for all monetary/quantity fields. Create indexes per 02-architecture (e.g. `economic_events`: walletAddress + networkId + blockTimestamp; `asset_positions`: walletAddress + networkId + assetContract; unique on `(txHash, networkId)` for events, `(walletAddress, networkId, assetContract)` for on_chain_balances).
- **Doc refs:** 02-architecture (System Context, Mongo collections), 01-domain (entity fields), ADR-002
- **DoD:** MongoConfig + index creation (or migration); integration test that persists and reads documents with Decimal128.
- **Dependencies:** T-001

**T-003 — Application config: caches, async, scheduler**
- **Module(s):** config
- **Description:** Configure Caffeine caches (spotPrice 5min/500, historical 24h/10k, snapshot 60min/200, crossWalletAvco 5min/2k, tokenMeta 24h/5k). Configure async executor and named pools: `backfill-executor`, `recalc-executor`, `sync-executor`, `scheduler-pool` with thread counts per 02-architecture. Scheduler for cron jobs only (no business logic).
- **Doc refs:** 02-architecture (Thread Pool Strategy, Cache Configuration), ADR-005
- **DoD:** Config classes; unit or integration test that executors and caches are created and used.
- **Dependencies:** T-001

---

## Ingestion

**T-004 — NetworkAdapter interface and EVM adapter**
- **Module(s):** ingestion (adapter)
- **Description:** Define `NetworkAdapter` interface (fetch transactions/blocks for a wallet×network, batch size contract). Implement `EvmNetworkAdapter`: `eth_getLogs` (or equivalent) in batches of 2000 blocks, RPC endpoint abstraction. Integrate `RpcEndpointRotator` with round-robin and exponential backoff (±20% jitter).
- **Doc refs:** 02-architecture (ingestion/adapter), 00-context (EVM RPC)
- **DoD:** Interface; EVM implementation; unit tests with mocked RPC; integration test against public RPC or Testcontainers if applicable.
- **Dependencies:** T-001, T-003

**T-005 — Solana network adapter**
- **Module(s):** ingestion (adapter)
- **Description:** Implement `SolanaNetworkAdapter`: getSignaturesForAddress + SPL token/balance resolution within batch and rate limits. Reuse retry/backoff and endpoint rotation pattern.
- **Doc refs:** 02-architecture (ingestion/adapter), 00-context (Solana Helius)
- **DoD:** Implementation; unit tests with mocked RPC; integration test where feasible.
- **Dependencies:** T-004

**T-006 — Transaction classifiers**
- **Module(s):** ingestion (classifier)
- **Description:** Implement `TxClassifier` dispatch and classifiers: `SwapClassifier`, `TransferClassifier`, `StakeClassifier`, `LpClassifier`, `LendClassifier`; `InternalTransferDetector` (counterparty in session); `InternalTransferReclassifier` (retroactive EXTERNAL_INBOUND → INTERNAL_TRANSFER when wallet added). Use `ProtocolRegistry` for protocol names. Classifiers output raw event shape for normalizer (no domain EconomicEvent yet).
- **Doc refs:** 01-domain (Event Types), 02-architecture (ingestion/classifier)
- **DoD:** All classifiers implemented; unit tests per classifier with fixture transactions; InternalTransferReclassifier tested with multi-wallet scenario.
- **Dependencies:** T-001

**T-007 — Economic event normalizer and gas cost**
- **Module(s):** ingestion (normalizer)
- **Description:** Implement `EconomicEventNormalizer`: raw classifier output → `EconomicEvent` (network-agnostic). Implement `GasCostCalculator`: gasUsed × gasPrice × native token price → USD. Native price from price resolver (injection point). Set `gasIncludedInBasis` per 03-accounting (BUY default true).
- **Doc refs:** 01-domain (EconomicEvent), 03-accounting (Gas Treatment), 02-architecture (normalizer)
- **DoD:** Normalizer and gas calculator; unit tests with stub price; verify event shape and gas fields.
- **Dependencies:** T-001, T-006

**T-008 — IdempotentEventStore**
- **Module(s):** ingestion (store)
- **Description:** Implement `IdempotentEventStore`: upsert economic events keyed by `(txHash, networkId)` uniqueness. No duplicate events for same tx; support MANUAL_COMPENSATING (keyed by synthetic id or clientId).
- **Doc refs:** 02-architecture (store), 01-domain INV-11
- **DoD:** Store implementation; integration test: double write same tx → single event; verify indexes.
- **Dependencies:** T-001, T-002

**T-009 — Backfill job and WalletAddedEvent**
- **Module(s):** ingestion (job), api (wallet)
- **Description:** On `WalletAddedEvent` (e.g. after POST /wallets), run `BackfillJobRunner` on `backfill-executor`: per-network parallel backfill (2 years). Per network: fetch via adapter → classify → normalize → resolve historical price (chain) → flag → IdempotentEventStore.upsert → trigger AVCO recalc for affected wallet×asset. Update `sync_status` (progressPct, syncBannerMessage, lastBlockSynced). On all networks complete: set status COMPLETE, run InternalTransferReclassifier.
- **Doc refs:** 02-architecture (Data Flow 1 — Initial Wallet Backfill), 00-context (2-year backfill)
- **DoD:** BackfillJobRunner, event listener; unit tests with mocks; integration test: add wallet → backfill runs and sync_status progresses (can use small window).
- **Dependencies:** T-004, T-005, T-006, T-007, T-008, T-015 (AvcoEngine), T-017 (RecalcJob/event), pricing chain for historical (T-020)

**T-010 — Incremental sync job**
- **Module(s):** ingestion (job)
- **Description:** Implement `IncrementalSyncJob`: `@Scheduled(fixedDelay=3_600_000)`. For each wallet×network with backfill complete, fetch from lastBlockSynced+1 to current block; same pipeline as backfill (classify → normalize → price → store → AVCO recalc). Update lastBlockSynced. Use `sync-executor` for parallelism.
- **Doc refs:** 02-architecture (Data Flow 2 — Incremental Sync)
- **DoD:** Scheduled job; unit tests; integration test: two runs, second run only new blocks.
- **Dependencies:** T-009 (pipeline), T-015

**T-011 — Sync status and progress tracker**
- **Module(s):** ingestion (job), domain
- **Description:** Maintain `sync_status` per (walletAddress, networkId): status (PENDING/RUNNING/COMPLETE/PARTIAL/FAILED), progressPct, lastBlockSynced, syncBannerMessage, backfillComplete. `SyncProgressTracker` updates progress during backfill/sync. API will read this for GET /wallets/{address}/status.
- **Doc refs:** 02-architecture (sync_status), 04-api (Get Wallet Sync Status)
- **DoD:** Persist sync_status; tracker updates; unit tests; integration test: backfill updates progress and status.
- **Dependencies:** T-002, T-009

---

## Current balance

**T-012 — OnChainBalanceStore**
- **Module(s):** ingestion (store)
- **Description:** Implement `OnChainBalanceStore`: upsert by (walletAddress, networkId, assetContract) UNIQUE; store quantity and capturedAt. Support native (e.g. assetContract = zero or sentinel). Used by balance poll and manual refresh.
- **Doc refs:** 02-architecture (on_chain_balances, store), 01-domain (On-Chain Balance)
- **DoD:** Store implementation; integration test: upsert and read by wallet+network+asset.
- **Dependencies:** T-002

**T-013 — CurrentBalancePollJob**
- **Module(s):** ingestion (job)
- **Description:** Implement `CurrentBalancePollJob`: `@Scheduled(fixedRate=600_000)` (10 min). For each (wallet, network) from sync_status, call RPC for native + token balances (from known assets for that wallet or config). Write to `OnChainBalanceStore.upsert`. No dependency on backfill completion.
- **Doc refs:** 02-architecture (Data Flow 2b, scheduler-pool), ADR-007
- **DoD:** Scheduled job; unit tests with mocked RPC and store; integration test optional (real RPC or stub).
- **Dependencies:** T-004, T-005, T-012

**T-014 — BalanceController POST /wallets/balances/refresh**
- **Module(s):** api
- **Description:** Implement `POST /api/v1/wallets/balances/refresh`: body `{ wallets, networks }`. Trigger same fetch as CurrentBalancePollJob for the given set (async or sync); return 202 and message. Updated balances visible on next GET /assets.
- **Doc refs:** 04-api (Trigger Manual Balance Refresh), 02-architecture (Manual refresh)
- **DoD:** Controller; integration test: POST then GET /assets shows updated onChainQuantity when implemented in assets response.
- **Dependencies:** T-012, T-013 (or direct call to same service used by job)

---

## Cost basis

**T-015 — AvcoEngine (per-wallet AVCO)**
- **Module(s):** costbasis (engine)
- **Description:** Implement `AvcoEngine`: load economic_events for (wallet, asset) ORDER BY blockTimestamp ASC (include MANUAL_COMPENSATING). Apply active `cost_basis_overrides` to on-chain events only; manual events use own priceUsd. Run AVCO formula (BUY/SELL/INTERNAL_TRANSFER per 03-accounting). On SELL: set realisedPnlUsd, avcoAtTimeOfSale. Set hasIncompleteHistory if chronologically first event is SELL or transfer-out. Persist asset_positions (quantity, perWalletAvco, totalCostBasisUsd, totalRealisedPnlUsd, hasIncompleteHistory, flags aggregate). All arithmetic BigDecimal/Decimal128.
- **Doc refs:** 03-accounting (AVCO Formula, Realised P&L), 01-domain (Invariants INV-01, INV-07, INV-08, INV-09), 02-architecture (AvcoEngine)
- **DoD:** Engine implementation; unit tests with event sequences (BUY/SELL, INTERNAL_TRANSFER, override, manual compensating); integration test: persist events → run engine → verify positions.
- **Dependencies:** T-001, T-002, T-008

**T-016 — CrossWalletAvcoAggregatorService**
- **Module(s):** costbasis (engine)
- **Description:** Implement on-request aggregation: given list of wallet addresses and assetSymbol, load all economic_events for that asset across those wallets; sort by blockTimestamp ASC; exclude INTERNAL_TRANSFER; run AVCO formula on merged timeline; return crossWalletAvco (and optionally quantity). Never persist. Use Caffeine cache (key: sorted(wallets)+assetSymbol, TTL 5min).
- **Doc refs:** 01-domain (Cross-Wallet AVCO, INV-04, INV-05), 03-accounting (Cross-Wallet AVCO), 02-architecture (CrossWalletAvcoAggregatorService)
- **DoD:** Service + cache; unit tests with multi-wallet event sets; verify INTERNAL_TRANSFER excluded and result not stored.
- **Dependencies:** T-015

**T-017 — RecalcJobService and OverrideService**
- **Module(s):** costbasis (override)
- **Description:** Implement `OverrideService`: PUT/DELETE override → upsert/deactivate in `cost_basis_overrides`; create RecalcJob (PENDING); publish OverrideSavedEvent. Implement `RecalcJobService`: consume override/manual events; run `AvcoEngine.replayFromBeginning` async on recalc-executor; set RecalcJob COMPLETE/FAILED. RecalcJob persisted; optional TTL cleanup (e.g. 24h).
- **Doc refs:** 02-architecture (Manual Override + Async Recalculation), 03-accounting (Manual Override), 04-api (Overrides, Recalculation Jobs)
- **DoD:** OverrideService + RecalcJobService; unit tests; integration test: PUT override → poll recalc status → COMPLETE and positions updated.
- **Dependencies:** T-015, T-002

**T-018 — Manual compensating transaction (write path)**
- **Module(s):** costbasis (event), ingestion (store)
- **Description:** Implement creation of manual compensating events: validate (assetSymbol or assetContract, quantityDelta, priceUsd when quantityDelta > 0). Idempotency by clientId: if event with clientId exists return 200 and existing event/jobId. Insert into economic_events (eventType=MANUAL_COMPENSATING, txHash null or synthetic id, clientId). Create RecalcJob and publish event for RecalcJobService. Optional DELETE manual event by id → remove event and trigger replay.
- **Doc refs:** 01-domain (Manual Compensating Transaction, INV-13, INV-14), 03-accounting (Manual Compensating Transaction), 02-architecture (Data Flow 5), ADR-008
- **DoD:** Service + IdempotentEventStore support for clientId; unit tests (idempotency, validation); integration test: POST with clientId twice → single event; DELETE triggers recalc.
- **Dependencies:** T-008, T-015, T-017

---

## Pricing

**T-019 — Historical price resolver chain**
- **Module(s):** pricing (resolver)
- **Description:** Implement `HistoricalPriceResolver` chain: (1) StablecoinResolver (USDC, USDT, DAI, GHO, USDe, FRAX → $1.00). (2) SwapDerivedResolver: from event/transaction tokenIn/tokenOut ratio. (3) CoinGeckoHistoricalResolver: `/coins/{id}/history` with date; token bucket 45 req/min; cache key (contractAddress, date) TTL 24h. Return price or UNKNOWN; on UNKNOWN caller flags event.
- **Doc refs:** 01-domain (Price Sources), 03-accounting (Price Resolution), 02-architecture (pricing/resolver)
- **DoD:** Chain and resolvers; unit tests per resolver; integration test with throttled/mocked CoinGecko.
- **Dependencies:** T-001, T-003 (cache)

**T-020 — SpotPriceResolver and price caches**
- **Module(s):** pricing
- **Description:** Implement `SpotPriceResolver` for current price (e.g. CoinGecko `/simple/price`). Used by SnapshotBuilder. Wire Caffeine: spotPrice TTL 5min, historical TTL 24h per 02-architecture.
- **Doc refs:** 02-architecture (SpotPriceResolver, Cache Configuration)
- **DoD:** SpotPriceResolver; cache config; unit tests; integration test with mocked API.
- **Dependencies:** T-003, T-019

---

## Snapshot

**T-021 — SnapshotBuilder**
- **Module(s):** snapshot
- **Description:** Implement `SnapshotBuilder`: for given (wallet, network) and snapshotTime (hour-truncated), load asset_positions, resolve spot price per asset via SpotPriceResolver, compute valueUsd and unrealisedPnlUsd per asset; build PortfolioSnapshot (per-wallet only). Idempotent upsert on (walletAddress, networkId, snapshotTime).
- **Doc refs:** 01-domain (PortfolioSnapshot, AssetSnapshot), 02-architecture (SnapshotBuilder), 03-accounting (Unrealised P&L)
- **DoD:** Builder + repository; unit tests; integration test: positions exist → build snapshot → document stored.
- **Dependencies:** T-015, T-020, T-002

**T-022 — SnapshotCronJob and SnapshotAggregationService**
- **Module(s):** snapshot
- **Description:** Implement `SnapshotCronJob`: after incremental sync (or on schedule), for each wallet×network run SnapshotBuilder for current hour. Implement `SnapshotAggregationService`: on-request aggregation for GET /portfolio/snapshots: query portfolio_snapshots by wallets + range; merge per-wallet snapshots into time-series (e.g. sum totalValueUsd per bucket); optional crossWalletAvco from CrossWalletAvcoAggregatorService. Use snapshot cache (sorted(wallets)+range, TTL 60min).
- **Doc refs:** 02-architecture (SnapshotCronJob, SnapshotAggregationService, Data Flow 2 and 3)
- **DoD:** Cron job + aggregation service; unit tests; integration test: build snapshots → GET snapshots returns aggregated series.
- **Dependencies:** T-021, T-016

---

## API and reconciliation

**T-023 — WalletController and SyncController**
- **Module(s):** api
- **Description:** Implement `POST /api/v1/wallets` (body: address, networks): validate address and networks; upsert sync_status PENDING; publish WalletAddedEvent; return 202 with syncId/message. Implement `GET /api/v1/wallets/{address}/status?network=`: return sync status, progressPct, syncBannerMessage. Implement `POST /api/v1/sync/refresh` (body: wallets, networks): trigger incremental sync for given set; return 202.
- **Doc refs:** 04-api (Wallets, Trigger Manual Sync), 02-architecture (api)
- **DoD:** Controllers; integration tests: POST wallets → 202 and status RUNNING; GET status; POST sync/refresh → 202.
- **Dependencies:** T-009, T-010, T-011, T-014

**T-024 — AssetController with reconciliation fields**
- **Module(s):** api
- **Description:** Implement `GET /api/v1/assets?wallets=...&network=`: load asset_positions for wallets (and optional network filter). For each position compute or read: onChainQuantity (from on_chain_balances), derivedQuantity (= quantity), balanceDiscrepancy (onChainQuantity − derivedQuantity), reconciliationStatus (MATCH | MISMATCH | NOT_APPLICABLE), showReconciliationWarning (true if MISMATCH and wallet history within 2 years). Attach spotPrice (from cache), crossWalletAvco (CrossWalletAvcoAggregatorService). Return DTOs with all 04-api fields including reconciliation.
- **Doc refs:** 04-api (Get Asset List), 01-domain (Reconciliation), 02-architecture (Data Flow 6)
- **DoD:** Controller and DTOs; integration tests: GET returns onChainQuantity, derivedQuantity, balanceDiscrepancy, reconciliationStatus, showReconciliationWarning; no RPC on GET.
- **Dependencies:** T-015, T-016, T-012, T-020

**T-025 — TransactionController**
- **Module(s):** api
- **Description:** Implement `GET /api/v1/assets/{assetId}/transactions`: cursor-based pagination (cursor, limit, direction=DESC|ASC). Return event list with nextCursor and hasMore. Include hasOverride per event.
- **Doc refs:** 04-api (Get Transaction History)
- **DoD:** Controller; integration test: pagination and direction.
- **Dependencies:** T-008, T-002

**T-026 — ChartController**
- **Module(s):** api
- **Description:** Implement `GET /api/v1/portfolio/snapshots` and `GET /api/v1/charts/asset/{symbol}`: delegate to SnapshotAggregationService; return range, wallets, dataPoints (timestamp, totalValueUsd, etc.) and asset chart (valueUsd, spotPriceUsd, perWalletAvco, unrealisedPnlUsd). No RPC; read from snapshots/cache.
- **Doc refs:** 04-api (Portfolio Snapshots, Get Asset Chart Data)
- **DoD:** Controllers; integration tests: GET returns correct shape and range.
- **Dependencies:** T-022

**T-027 — SnapshotController**
- **Module(s):** api
- **Description:** Expose GET portfolio/snapshots under SnapshotController if not under ChartController; align with 04-api (GET /api/v1/portfolio/snapshots).
- **Doc refs:** 04-api (Portfolio Snapshots), 02-architecture (SnapshotController)
- **DoD:** Controller; integration test.
- **Dependencies:** T-022

**T-028 — OverrideController, ManualTransactionController, RecalcController**
- **Module(s):** api
- **Description:** Implement `PUT /api/v1/transactions/{eventId}/override` and `DELETE .../override`: body/params per 04-api; call OverrideService; return 202 with jobId. Implement `POST /api/v1/transactions/manual`: body with clientId, quantityDelta, priceUsd (when >0), etc.; idempotency by clientId (200 with existing); return 202 with jobId. Optional `DELETE /api/v1/transactions/manual/{eventId}`. Implement `GET /api/v1/recalc/status/{jobId}`: return job status, newPerWalletAvco when COMPLETE.
- **Doc refs:** 04-api (Overrides, Manual Compensating Transaction, Recalculation Jobs)
- **DoD:** All three controllers; integration tests: override flow, manual tx with clientId idempotency, recalc polling.
- **Dependencies:** T-017, T-018

**T-029 — Reconciliation status and showReconciliationWarning**
- **Module(s):** costbasis or api (read path), domain
- **Description:** Ensure reconciliation is implemented for GET /assets: compute or store reconciliationStatus (MATCH / MISMATCH / NOT_APPLICABLE) by comparing on_chain_balances.quantity with asset_positions.quantity (tolerance ε). Set showReconciliationWarning true when MISMATCH and wallet history is within 2 years (e.g. oldest event within backfill window). GET /assets returns onChainQuantity, derivedQuantity, balanceDiscrepancy, reconciliationStatus, showReconciliationWarning.
- **Doc refs:** 01-domain (Reconciliation table), 04-api (Get Asset List — reconciliation fields), 02-architecture (Data Flow 6)
- **DoD:** Logic in read path or position update path; unit tests for status and warning; integration test in AssetController.
- **Dependencies:** T-012, T-015, T-024

---

## Summary dependency order

1. **T-001** → T-002, T-003, T-006, T-007, T-008, T-019
2. **T-002** → T-008, T-011, T-012, T-015, T-021
3. **T-003** → T-004, T-019, T-020
4. **T-004, T-005** → T-009, T-013
5. **T-006, T-007** → T-009
6. **T-008** → T-009, T-015, T-018
7. **T-015** → T-009, T-010, T-016, T-017, T-021, T-024
8. **T-017** → T-009, T-018, T-028
9. **T-019, T-020** → T-009, T-021
10. **T-012** → T-013, T-014, T-024, T-029
11. **T-021, T-022** → T-026, T-027
12. **T-024** → T-029 (reconciliation fields in same endpoint)

---

## Where to implement

| Task IDs | File |
|----------|------|
| T-001, T-002, T-003 | 00-foundation.md |
| T-004 … T-008 | 00-ingestion-core.md |
| T-015, T-016 | 00-cost-basis-engine.md |
| T-019, T-020 | 00-pricing.md |
| T-009, T-023 | 01-add-wallet-backfill.md |
| T-011, T-023 (partial) | 02-wallet-sync-status.md |
| T-010, T-023 (partial) | 03-incremental-sync.md |
| T-012, T-013, T-014 | 04-current-balance.md |
| T-017, T-028 | 05-override-recalc.md |
| T-018, T-028 (partial) | 06-manual-compensating-transaction.md |
| T-024, T-029 | 07-reconciliation-get-assets.md |
| T-025 | 08-transaction-history.md |
| T-021, T-022, T-026, T-027 | 09-portfolio-snapshots-charts.md |
