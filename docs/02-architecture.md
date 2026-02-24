# WalletRadar — Architecture

> **Version:** SAD v2.0 (updated per BA Review Report v1.0)  
> **Style:** Modular Monolith → Microservices (Phase 3)  
> **Stack:** Java 21 · Spring Boot · MongoDB 7 · Docker  
> **Build:** Gradle (backend). **Repository:** Monorepo — backend and frontend in one repo.

---

## Build and repository layout

- **Build tool:** **Gradle** for the backend (not Maven). Use Gradle Wrapper (`gradlew`) at backend root.
- **Monorepo:** Backend and frontend live in the same repository.
  - **Backend:** Gradle project (e.g. root or `backend/` subdirectory). All Java 21, Spring Boot, domain modules.
  - **Frontend:** Angular app in a dedicated directory (e.g. `frontend/`). Own `package.json` and build.
- Root may be a Gradle multi-project including the backend (and optionally frontend build), or `backend/` and `frontend/` may be built separately. See **ADR-010** for the decision.

---

## Architectural Style

WalletRadar MVP is a **modular monolith** — a single Spring Boot JAR with domain-isolated packages. Inter-module communication uses only Spring `ApplicationEvent`. Package boundaries are enforced by **ArchUnit** tests in CI.

**Core principles:**
- **Snapshot-first reads** — GET endpoints make zero RPC calls and zero heavy computation
- **Event-driven internally** — Spring Events replace message broker infrastructure in MVP
- **Deterministic AVCO** — same inputs always produce same output regardless of execution order
- **crossWalletAvco never stored** — always computed on-request for exact wallet set in each call
- **Free-first data sources** — public RPC → stablecoin hardcode → swap-derived → CoinGecko → UNKNOWN

---

## System Context

```
┌────────────────────────────────────────────────────────────────────┐
│                        WALLETRADAR SYSTEM                          │
│                                                                    │
│  ┌─────────────┐  REST/JSON  ┌──────────────────────────────────┐  │
│  │   Angular   │◄───────────►│    Spring Boot Monolith          │  │
│  │  (Browser)  │             │         (Java 21)                │  │
│  │             │             │  ┌─────────────┐ ┌────────────┐  │  │
│  │ localStorage│             │  │  REST API   │ │ Scheduled  │  │  │
│  │ wallet addrs│             │  │   Layer     │ │   Jobs     │  │  │
│  └─────────────┘             │  └─────────────┘ └────────────┘  │  │
│                              └────────────────┬─────────────────┘  │
│                                               │                    │
│                    ┌──────────────────────────┤                    │
│                    │                          │                    │
│  ┌─────────────────▼────┐    ┌────────────────▼──────────────┐    │
│  │     MongoDB 7         │    │       External Sources         │    │
│  │  (self-hosted Docker) │    │  EVM RPC (Ankr/Cloudflare)    │    │
│  │  raw_transactions     │    │  Solana RPC (Helius Free)     │    │
│  │  economic_events      │    │  CoinGecko Free API           │    │
│  │  asset_positions      │    └───────────────────────────────┘    │
│  │  portfolio_snapshots  │  ← per-wallet ONLY                      │
│  │  cost_basis_overrides │                                         │
│  │  sync_status          │                                         │
│  │  recalc_jobs          │                                         │
│  │  on_chain_balances    │  ← per (wallet, network, asset), independent of backfill │
│  │  (manual events in economic_events as eventType=MANUAL_COMPENSATING)             │
│  └───────────────────────┘                                         │
└────────────────────────────────────────────────────────────────────┘
```

---

## Module Map

```
com.walletradar/
│
├── api/              REST controllers — thin layer, zero business logic
├── ingestion/        RPC adapters, tx classification, backfill & sync jobs
├── costbasis/        AVCO engine, flag service, overrides, recalc jobs
├── pricing/          Price resolver chain (stablecoin → swap → CoinGecko)
├── snapshot/         Snapshot builder (per-wallet cron) + on-request aggregation
├── domain/           Shared value objects — NO business logic
├── config/           Spring configuration (cache, async, scheduler, OTel)
└── common/           StablecoinRegistry, RateLimiter, RetryPolicy
```

### Module Dependency Rules (ArchUnit enforced)

| Module | May call | Must NOT call |
|--------|----------|---------------|
| `api` | All service interfaces via DI | Any repository or adapter directly |
| `ingestion` | `pricing`, `domain`, `common` | `costbasis`, `snapshot`, `api` |
| `costbasis` | `domain`, `common`, `pricing` (read-only) | `ingestion`, `snapshot`, `api` |
| `pricing` | `common` | `ingestion`, `costbasis`, `snapshot`, `api` |
| `snapshot` | `costbasis` (read-only services), `pricing`, `domain` | `ingestion`, `api` |
| `domain` | `common` | Any other module |

---

## Spring Boot Packages

```
com.walletradar
│
├── api/
│   ├── WalletController          POST /wallets, GET /wallets/{addr}/status
│   ├── AssetController           GET /assets
│   ├── TransactionController     GET /assets/{id}/transactions (cursor pagination)
│   ├── ChartController           GET /charts/portfolio, /charts/asset/{symbol}
│   ├── SnapshotController        GET /portfolio/snapshots
│   ├── OverrideController        PUT/DELETE /transactions/{id}/override
│   ├── ManualTransactionController   POST /transactions/manual (optional DELETE)
│   ├── RecalcController          GET /recalc/status/{jobId} (used for override and manual-tx recalc)
│   └── SyncController            POST /sync/refresh
│   └── BalanceController        POST /wallets/balances/refresh  (manual)
│
├── ingestion/
│   ├── adapter/
│   │   ├── NetworkAdapter        interface
│   │   ├── RpcEndpointRotator    round-robin, exponential backoff
│   │   ├── evm/
│   │   │   ├── EvmNetworkAdapter     eth_getLogs, batch size per network (default 2000), see ADR-011
│   │   │   ├── EvmRpcClient
│   │   │   └── WebClientEvmRpcClient
│   │   └── solana/
│   │       ├── SolanaNetworkAdapter  getSignaturesForAddress + SPL
│   │       ├── SolanaRpcClient
│   │       └── WebClientSolanaRpcClient
│   │   Config: network settings under walletradar.ingestion.network (per NetworkId): urls (RPC list), batch-block-size; see ADR-012.
│   ├── classifier/
│   │   ├── TxClassifier          dispatch by tx shape
│   │   ├── SwapClassifier
│   │   ├── TransferClassifier
│   │   ├── StakeClassifier
│   │   ├── LpClassifier
│   │   ├── LendClassifier
│   │   ├── InternalTransferDetector
│   │   ├── InternalTransferReclassifier   retroactive re-classify on wallet add
│   │   └── ProtocolRegistry
│   ├── normalizer/
│   │   ├── EconomicEventNormalizer        raw → EconomicEvent
│   │   └── GasCostCalculator             gas × native price → USD
│   ├── job/
│   │   ├── BackfillJobRunner             @EventListener(WalletAddedEvent)
│   │   ├── IncrementalSyncJob            @Scheduled fixedDelay=3_600_000
│   │   ├── CurrentBalancePollJob         @Scheduled fixedRate=600_000  (every 10 min)
│   │   └── SyncProgressTracker           progressPct + syncBannerMessage
│   └── store/
│   │   ├── IdempotentEventStore          upsert on txHash+networkId UNIQUE
│   │   └── OnChainBalanceStore           upsert on (walletAddress, networkId, assetContract) UNIQUE
│
├── costbasis/
│   ├── engine/
│   │   ├── AvcoEngine                    per-wallet chronological AVCO
│   │   │                                 loads economic_events (on-chain + MANUAL_COMPENSATING) ORDER BY blockTimestamp ASC
│   │   │                                 applies cost_basis_overrides to on-chain events only; manual events carry own priceUsd
│   │   │                                 computes realisedPnlUsd on SELL
│   │   │                                 sets hasIncompleteHistory if first event=SELL
│   │   └── CrossWalletAvcoAggregatorService   ON-REQUEST only, never stored
│   ├── flag/
│   │   ├── FlagService
│   │   └── FlagCode                      enum
│   ├── override/
│   │   ├── OverrideService              setOverride, revertOverride (overrides stored via CostBasisOverrideRepository in domain)
│   │   ├── RecalcJobService
│   │   └── CostBasisOverrideRepository  (domain) override persistence
│   └── event/
│       ├── OverrideSavedEvent
│       └── RecalcCompleteEvent
│
├── pricing/
│   ├── HistoricalPriceResolver           chain-of-responsibility
│   ├── SpotPriceResolver                 CoinGecko /simple/price (snapshot job only)
│   ├── resolver/
│   │   ├── StablecoinResolver            USDC/USDT/DAI/GHO/USDe/FRAX → $1.00
│   │   ├── SwapDerivedResolver           tokenIn/tokenOut ratio
│   │   └── CoinGeckoHistoricalResolver   /coins/{id}/history + token bucket 45 req/min
│   └── cache/
│       └── PriceCacheConfig              spotPrice TTL 5min, historical TTL 24h
│
├── snapshot/
│   ├── SnapshotBuilder                   per-wallet snapshot, idempotent upsert
│   ├── SnapshotAggregationService        ON-REQUEST aggregation from per-wallet snapshots
│   ├── SnapshotCronJob                   @Scheduled — per-wallet ONLY, no aggregate cron
│   └── SnapshotRepository
│
├── domain/
│   ├── EconomicEvent
│   ├── EconomicEventType                 enum
│   ├── AssetPosition
│   ├── NetworkId                         enum
│   ├── FlagCode                          enum
│   └── RecalcJob
│
├── config/
│   ├── CaffeineConfig                    5 caches with TTL and size bounds
│   ├── MongoConfig                       indexes, Decimal128 codec
│   ├── AsyncConfig                       4 dedicated thread pools
│   ├── SchedulerConfig
│   └── OpenTelemetryConfig               OTLP exporter (optional)
│
└── common/
    ├── StablecoinRegistry
    ├── RateLimiter                        token bucket, 45 req/min
    └── RetryPolicy                        exponential backoff ±20% jitter
```

---

## Thread Pool Strategy

| Pool | Threads | Used By |
|------|---------|---------|
| `backfill-executor` | 2 per network (max 18) | `BackfillJobRunner` parallel per-network fetch |
| `recalc-executor` | 4 | `AvcoEngine.replayFromBeginning` (@Async) — used after override and after manual compensating transaction |
| `sync-executor` | 3 | `IncrementalSyncJob` parallel wallets |
| `scheduler-pool` | 2 | `@Scheduled` cron jobs (IncrementalSync, SnapshotCron, **CurrentBalancePoll** every 10 min) |

---

## Cache Configuration

| Cache | Key | TTL | Max Size |
|-------|-----|-----|----------|
| `spotPriceCache` | contractAddress+date | 5 min | 500 |
| `historicalPriceCache` | contractAddress+ISODate | 24 h | 10 000 |
| `snapshotCache` | sorted(wallets)+range | 60 min | 200 |
| `crossWalletAvcoCache` | sorted(wallets)+assetSymbol | 5 min | 2 000 |
| `tokenMetaCache` | contractAddress+networkId | 24 h | 5 000 |

---

## Data Flows

### 1. Initial Wallet Backfill

```
POST /wallets
  → validate → upsert sync_status(PENDING) → 202 → WalletAddedEvent
  → BackfillJobRunner (@EventListener, backfill-executor)
    → per-network parallel (CompletableFuture)
      → EVM/Solana Adapter (EVM batch size per network, default 2000 — ADR-011)
      → TxClassifier → EconomicEventNormalizer
      → HistoricalPriceResolver (Stablecoin → Swap → CoinGecko/throttled → UNKNOWN)
      → FlagService
      → IdempotentEventStore.upsert()
      → AvcoEngine.recalculateForWallet()
          → on SELL: compute realisedPnlUsd, avcoAtTimeOfSale
          → if first event is SELL: set hasIncompleteHistory=true
      → update sync_status (progressPct, syncBannerMessage)
  → [all networks complete]
  → sync_status(COMPLETE), syncBannerMessage=null
  → InternalTransferReclassifier
      → scan economic_events WHERE counterpartyAddress IN {all session wallets}
      → reclassify EXTERNAL_INBOUND → INTERNAL_TRANSFER
      → replay AVCO for affected assets
```

### 2. Incremental Sync (Hourly Cron)

```
@Scheduled(fixedDelay=3_600_000)
  → find wallets WHERE backfillComplete=true
  → for each wallet×network (sync-executor):
      → fetch delta: lastBlockSynced+1 → currentBlock
      → same pipeline as backfill
      → update lastBlockSynced
  → trigger SnapshotCronJob
      → for each wallet×network:
          → SnapshotBuilder.build()
              → load asset_positions
              → SpotPriceResolver.resolve() (CoinGecko, Caffeine TTL 5min)
              → UPSERT portfolio_snapshots on (walletAddress, networkId, snapshotTime) UNIQUE
```

### 2b. Current Balance Poll (every 10 min, independent of backfill)

```
@Scheduled(fixedRate=600_000)
  → find all (walletAddress, networkId) from sync_status
  → for each: RPC — native balance + token balances (e.g. from known asset list for that wallet or config)
  → OnChainBalanceStore.upsert(walletAddress, networkId, assetContract|native, quantity, capturedAt)
```
**Manual refresh:** `POST /wallets/balances/refresh` with `{ wallets, networks }` triggers the same fetch immediately for the given set (no wait for next 10 min). Response 202; updated balances appear on next GET /assets (or dedicated GET balances endpoint).

### 3. Portfolio Read (Zero RPC)

```
GET /portfolio/snapshots?wallets=0xA,0xB&range=7D
  → Caffeine snapshotCache (TTL 60min)
  → cache miss → query portfolio_snapshots WHERE walletAddress IN [0xA,0xB]
  → SnapshotAggregationService.aggregateForRequest()
      → sum totalValueUsd per hourly bucket
      → merge asset lists
      → CrossWalletAvcoAggregatorService.compute() (Caffeine crossWalletAvcoCache TTL 5min)
  → return time-series []   target: <150ms

GET /assets?wallets=0xA,0xB
  → query asset_positions (indexed, <10ms)
  → attach spotPrice from Caffeine
  → CrossWalletAvcoAggregatorService.compute(wallets, assetSymbol)
  → return AssetPositionDTO[]   target: <80ms
```

### 4. Manual Override + Async Recalculation

```
PUT /transactions/{eventId}/override { priceUsd, note }
  → upsert cost_basis_overrides (isActive=true)
  → create RecalcJob (PENDING)
  → publish OverrideSavedEvent
  → return 202 { jobId }

@Async (recalc-executor):
  → AvcoEngine.replayFromBeginning(wallet, asset)
      → load ALL events ORDER BY blockTimestamp ASC (on-chain + MANUAL_COMPENSATING)
      → apply active overrides to on-chain events only
      → recompute AVCO + realisedPnlUsd on each SELL
      → update asset_positions
      → set RecalcJob(COMPLETE)

Frontend: GET /recalc/status/{jobId} every 2s
```

### 5. Manual Compensating Transaction

```
POST /transactions/manual { walletAddress, networkId, assetSymbol|assetContract, quantityDelta, priceUsd?, timestamp?, note, clientId }
  → idempotency: if clientId already exists, return existing event / 200
  → insert economic_events (eventType=MANUAL_COMPENSATING, txHash=null or synthetic id)
  → create RecalcJob (PENDING)
  → publish event (e.g. ManualTransactionSavedEvent)
  → return 202 { jobId }

@Async (recalc-executor):
  → AvcoEngine.replayFromBeginning(wallet, asset)
      → load economic_events (including MANUAL_COMPENSATING) ORDER BY blockTimestamp ASC
      → manual events participate in AVCO like any other event (priceUsd required if quantityDelta > 0)
      → update asset_positions
      → set RecalcJob(COMPLETE)

Optional: DELETE /transactions/manual/{eventId} — remove manual event, trigger same replay.
```

### 6. Reconciliation (On-Chain vs Derived)

```
Read path (GET /assets):
  → load asset_positions for requested wallets
  → load on_chain_balances for same (wallet, network, asset) — or read from positions if stored
  → for each position: compare onChainQuantity (from on_chain_balances) with derived quantity (asset_positions.quantity)
  → set reconciliationStatus: MATCH | MISMATCH | NOT_APPLICABLE (e.g. no on-chain data or wallet older than 2 years)
  → set showReconciliationWarning: true if MISMATCH and wallet history "young" (< 2 years)
  → return onChainQuantity, derivedQuantity, balanceDiscrepancy, reconciliationStatus, showReconciliationWarning
```

---

## Scaling Path

### Phase 1 — MVP (Single Host)
- Hetzner CX31 (4vCPU/8GB) — $14/month
- Spring Boot JAR + MongoDB Docker + Nginx
- Caffeine in-process cache
- `docker-compose up -d`

### Phase 2 — Horizontal (trigger: >500 sessions or Caffeine eviction >30%)
- Nginx load balancer → 3× stateless Boot instances
- Redis Cluster replaces Caffeine (`@Cacheable` swap — config only, zero business logic change)
- MongoDB Replica Set (1 primary + 2 secondaries)

### Phase 3 — Domain Microservices
Extract when scaling boundary clearly identified:

| Service | Trigger |
|---------|---------|
| `ingestion-service` | Backfill CPU competes with read latency |
| `pricing-service` | CoinGecko rate limit needs dedicated process |
| `snapshot-service` | Snapshot computation blocks app threads |
| `costbasis-service` | AVCO recalc latency becomes UX bottleneck |
| `api-gateway` | Per-route auth (SIWE) required |

---

## Open Items

| ID | Question | Status |
|----|----------|--------|
| ~~`OPEN-01`~~ | ~~AVCO replay scope after `InternalTransferReclassifier`~~ | **Resolved:** Option B — replay AVCO for **both** source and destination wallets. |
| ~~`OPEN-02`~~ | ~~`SnapshotCronJob` fault tolerance~~ | **Resolved:** Idempotent upsert on `(walletAddress, networkId, snapshotTime)` for MVP; no transactional batch. |
| ~~`OPEN-03`~~ | ~~Transaction history default sort~~ | **Resolved:** Default `DESC` (newest first), optional `ASC`. |
