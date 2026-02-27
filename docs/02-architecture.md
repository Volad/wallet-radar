# WalletRadar — Architecture

> **Version:** SAD v2.0 (updated per BA Review Report v1.0)  
> **Style:** Modular Monolith → Microservices (Phase 3)  
> **Stack:** Java 21 · Spring Boot · MongoDB 7 · Docker  
> **Build:** Gradle (backend). **Repository:** Monorepo — backend and frontend in one repo.

---

## Build and repository layout

- **Build tool:** **Gradle** for the backend (not Maven). Gradle Wrapper at project root: `./gradlew`; backend tasks: `./gradlew :backend:test`, `./gradlew :backend:bootRun`, `./gradlew :backend:build`.
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
- **ADR-025 canonical flow** — `normalized_transactions` (with `legs[]`) is the canonical transaction/accounting input; `economic_events` is legacy-only during migration

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
│  │  normalized_transactions │ │  CoinGecko Free API           │    │
│  │  asset_positions      │    └───────────────────────────────┘    │
│  │  portfolio_snapshots  │  ← per-wallet ONLY                      │
│  │  cost_basis_overrides │                                         │
│  │  sync_status          │                                         │
│  │  backfill_segments    │  ← per-sync segment progress/state      │
│  │  recalc_jobs          │                                         │
│  │  on_chain_balances    │  ← per (wallet, network, asset), independent of backfill │
│  │  (manual operations represented in normalized_transactions type=MANUAL_COMPENSATING) │
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
| `pricing` | `domain`, `common` | `ingestion`, `costbasis`, `snapshot`, `api` |
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
│   │   │   ├── EvmNetworkAdapter     eth_getLogs (Transfer), then eth_getTransactionReceipt; stores FULL receipt in rawData (ADR-020)
│   │   │   ├── EvmRpcClient          interface; includes batchCall() for JSON-RPC batch requests (ADR-016)
│   │   │   ├── WebClientEvmRpcClient  batchCall() sends array of JSON-RPC requests in one HTTP POST; falls back to sequential on failure
│   │   │   ├── EstimatingBlockTimestampResolver   linear interpolation from 2 anchor blocks → O(1) RPC per network instead of per-block (ADR-016)
│   │   │   └── RpcRequest             DTO for JSON-RPC batch request payloads
│   │   └── solana/
│   │       ├── SolanaNetworkAdapter  getSignaturesForAddress + getTransaction; stores full tx + sigInfo in rawData (ADR-020)
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
│   │   ├── NormalizedTransactionBuilder   raw classification result → NormalizedTransaction (legs + status)
│   │   └── GasCostCalculator             gas × native price → USD
│   ├── pipeline/
│   │   ├── classification/
│   │   │   └── ClassificationProcessor       classify raw → build legs → upsert normalized_transactions with initial status (ADR-025)
│   │   └── enrichment/
│   │       └── InlineSwapPriceEnricher      inline stablecoin-leg swap pricing before upsert
│   ├── sync/
│   │   └── progress/
│   │       └── SyncProgressTracker          progressPct + syncBannerMessage + retry backoff
│   ├── wallet/
│   │   ├── command/
│   │   │   └── WalletBackfillService        addWallet: upsert sync_status + publish WalletAddedEvent
│   │   └── query/
│   │       └── WalletSyncStatusService      read-only sync status for API layer
│   ├── job/
│   │   ├── backfill/
│   │   │   ├── BackfillJobRunner             orchestrator: queue, worker loops, event listeners, retry scheduler, reclassify when idle (ADR-014, ADR-017, ADR-021)
│   │   │   ├── BackfillNetworkExecutor       runs backfill for one (wallet, network): Phase 1 only; persistent segment planning/state in backfill_segments; raw fetch → RawFetchCompleteEvent (ADR-021)
│   │   │   ├── RawFetchSegmentProcessor      Phase 1: fetch from RPC → store FULL payload in raw_transactions (ADR-020)
│   │   │   └── BackfillProgressCallback      functional interface for segment progress reporting
│   │   ├── classification/
│   │   │   └── RawTransactionClassifierJob   @Scheduled(90s) + RawFetchCompleteEvent: process PENDING raw into normalized transactions
│   │   ├── normalization/
│   │   │   ├── ClarificationJob             @Scheduled: process PENDING_CLARIFICATION only (selective extra RPC)
│   │   │   ├── PricingJob                   @Scheduled: PENDING_PRICE -> PENDING_STAT
│   │   │   └── StatJob                      @Scheduled: PENDING_STAT -> CONFIRMED
│   │   ├── pricing/
│   │   │   └── DeferredPriceResolutionJob    @Scheduled(2–5 min): find PRICE_PENDING wallets → resolve per wallet → RecalculateWalletRequestEvent (ADR-016, ADR-021)
│   │   ├── sync/
│   │   │   └── IncrementalSyncJob            @Scheduled fixedDelay=3_600_000
│   │   └── CurrentBalancePollJob             @Scheduled fixedRate=600_000  (every 10 min)
│   └── store/
│   │   ├── IdempotentEventStore          upsert on (txHash, networkId, walletAddress, assetContract) UNIQUE sparse; MANUAL by clientId
│   │   └── OnChainBalanceStore           upsert on (walletAddress, networkId, assetContract) UNIQUE
│
├── costbasis/
│   ├── engine/
│   │   ├── AvcoEngine                    per-wallet chronological AVCO
│   │   │                                 loads CONFIRMED normalized transaction legs ORDER BY blockTimestamp ASC
│   │   │                                 applies cost_basis_overrides to on-chain operations only; manual operations carry own priceUsd
│   │   │                                 computes realisedPnlUsd on SELL legs
│   │   │                                 sets hasIncompleteHistory if first event=SELL
│   │   ├── AvcoEventTypeHelper              classifies event types for AVCO formula
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
│   │   ├── CounterpartPriceResolver      resolves counterpart token price (avoids recursion in SwapDerived)
│   │   └── CoinGeckoHistoricalResolver   /coins/{id}/history + token bucket 45 req/min
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
│   ├── NormalizedTransaction             canonical operation + legs[] + status
│   ├── NormalizedTransactionStatus       PENDING_CLARIFICATION | PENDING_PRICE | PENDING_STAT | CONFIRMED | NEEDS_REVIEW
│   ├── AssetPosition
│   ├── RawTransaction                    raw_transactions: txHash, networkId, walletAddress, blockNumber/slot, rawData (full RPC), classificationStatus, createdAt (ADR-020)
│   ├── SyncStatus                        sync_status: rawFetchComplete, classificationComplete, classificationProgressPct (ADR-020)
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
| `backfill-coordinator-executor` | 1 | Runs `scheduleBackfillWork` and `runReclassifyAndRecalc` when all futures complete; does not consume worker threads |
| `backfill-executor` | 4 (max 18) | Shared pool for all (wallet, network) backfill tasks and parallel block-range segments within each task (ADR-016 T-OPT-6); free threads take next PENDING/FAILED from queue |
| `recalc-executor` | 4 | `AvcoEngine.replayFromBeginning` (@Async) — used after override and after manual compensating transaction |
| `sync-executor` | 3 | `IncrementalSyncJob` parallel wallets |
| `scheduler-pool` | 2 | `@Scheduled` cron jobs (IncrementalSync, SnapshotCron, CurrentBalancePoll, **DeferredPriceResolutionJob** every 2–5 min) |

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

## Canonical Pipeline (ADR-025)

`normalized_transactions` is the canonical operation pipeline.

```
raw_transactions (immutable)
  -> initial classification -> normalized_transactions(status=PENDING_* , legs[] partial or full)
  -> ClarificationJob: PENDING_CLARIFICATION only
       -> selective extra RPC enrichment (trace/native leg) only when needed
  -> PricingJob: PENDING_PRICE
       -> stablecoin/swap-derived/coingecko
  -> StatJob: PENDING_STAT
       -> final consistency checks, AVCO-ready legs
  -> CONFIRMED
       -> visible to UI and used by AVCO engine
```

Status machine:
- `PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`
- `NEEDS_REVIEW` for unresolved ambiguity after clarification retries.

AVCO input:
- only `normalized_transactions.status=CONFIRMED` legs are used for accounting replay.

## Legacy Data Flows (Pre-ADR-025)

### 1. Initial Wallet Backfill

ADR-021: **Backfill = Phase 1 only** (raw fetch & store). **Classifier** is a separate job with its own lifecycle. See ADR-016 (T-OPT), ADR-020 (split fetch vs classify), ADR-021 (classifier as separate process).

```
POST /wallets
  → validate → upsert sync_status(PENDING) → 202 → WalletAddedEvent
  → publish BalanceRefreshRequestedEvent(wallet, networks) [async, non-blocking]
  → BackfillJobRunner (@EventListener, backfill-executor) — orchestrator (ADR-017)
    → per-network parallel (CompletableFuture)
    → delegates to BackfillNetworkExecutor.runBackfillForNetwork()

      ── Phase 1 ONLY: Raw Fetch & Store (BackfillNetworkExecutor + RawFetchSegmentProcessor) ──

      → BackfillNetworkExecutor:
          → Create persistent segment plan in backfill_segments (linked by syncStatusId) once per sync_status:
              → segment fields: segmentIndex, fromBlock, toBlock, status(PENDING|RUNNING|COMPLETE|FAILED), progressPct, lastProcessedBlock, retryCount, errorMessage, timestamps
              → segment plan is not recalculated on retries/restarts
              → max segment count is capped (365)
          → Recover stale RUNNING segments (updatedAt older than configured threshold; default 3 minutes) → PENDING
          → Execute only PENDING/FAILED segments using virtual threads, capped by parallelSegmentWorkers
      → RawFetchSegmentProcessor.processSegment() per segment:
          → EVM: eth_getLogs (batch) + eth_getTransactionReceipt (batch)
              → store FULL receipt in raw_transactions.rawData (blockNumber, blockHash, logs, gasUsed, status, from, to, …)
          → Solana: getSignaturesForAddress + getTransaction
              → store full transaction + sigInfo (signature, slot, blockTime, err?, confirmationStatus?) in raw_transactions.rawData
          → Upsert raw_transactions (txHash, networkId) UNIQUE; set walletAddress, blockNumber/slot, classificationStatus=PENDING, createdAt
          → BackfillProgressCallback.reportProgress() → update segment progress (progress by blocks inside segment)
      → BackfillRunningProgressJob (@Scheduled, fixedDelay=progress-update-interval-ms) recomputes RUNNING sync_status from segment state:
          → progressPct = average(segment.progressPct) for the sync
          → banner = completeSegments / totalSegments
      → when all segments COMPLETE:
          → setRawFetchComplete() → publish RawFetchCompleteEvent(wallet, networkId, lastBlockSynced)
          → setComplete() — backfillComplete = rawFetchComplete (ADR-021)
      → when any segment FAILED:
          → set sync_status FAILED (retry scheduling remains driven by sync_status)

      ── SEPARATE: RawTransactionClassifierJob (ADR-021) ──

      Triggers: RawFetchCompleteEvent (immediate) + @Scheduled(90s)
      → Read raw_transactions WHERE (walletAddress, networkId, classificationStatus=PENDING) ORDER BY blockNumber/slot ASC
      → Per batch: EstimatingBlockTimestampResolver.calibrate() [2 RPC], nativePriceCache
      → ClassificationProcessor.processBatch() → TxClassifierDispatcher → EconomicEventNormalizer → InlineSwapPriceEnricher
      → IdempotentEventStore.upsert() to economic_events
      → Set raw_transactions.classificationStatus = COMPLETE (or FAILED on error)
      (no event published)

      ── DeferredPriceResolutionJob (@Scheduled 2–5 min) ──

      → findDistinctWalletAddressesByFlagCode(PRICE_PENDING)
      → for each wallet: resolveForWallet() → publish RecalculateWalletRequestEvent(walletAddress)
      → AvcoEngine.recalculateForWallet() (async)

  → InternalTransferReclassifier: @Scheduled(5 min) when backfill queue empty
      → scan economic_events WHERE counterpartyAddress IN {all session wallets}
      → reclassify EXTERNAL_INBOUND → INTERNAL_TRANSFER
      → replay AVCO for affected assets

  On failure:
  → BackfillNetworkExecutor: marks failed segments in backfill_segments and sets sync_status(FAILED), retryCount++, nextRetryAfter = exponential backoff (2^n min, max 60 min) + jitter
  → BackfillJobRunner: @Scheduled retryFailedBackfills (every 2 min) re-enqueues eligible items via sync_status
      → segment mode (backfill_segments exists): no ABANDONED transition; retries continue until segments converge to COMPLETE
      → legacy mode (no backfill_segments): maxRetries still leads to ABANDONED
  → In-flight dedup: ConcurrentHashMap prevents duplicate enqueue of same (wallet, network)

  In parallel (balance path):
  → BalanceRefreshRequestedEvent listener calls shared BalanceRefreshService
      → RPC native + token balances
      → OnChainBalanceStore.upsert(walletAddress, networkId, assetContract|native, quantity, capturedAt)
      → failures do not fail wallet add/backfill; retry via existing poll/manual paths
```

**RawTransaction schema (raw_transactions collection):**
- `txHash`, `networkId`, `walletAddress`, `blockNumber` (EVM) or `slot` (Solana), `rawData` (full RPC payload), `classificationStatus` (PENDING|COMPLETE|FAILED), `createdAt`
- Indexes: `(txHash, networkId)` UNIQUE; `(walletAddress, networkId, blockNumber)` ASC (EVM) / `(walletAddress, networkId, slot)` ASC (Solana); `(walletAddress, networkId, classificationStatus)`

**SyncStatus extensions:** `rawFetchComplete`, `classificationComplete`, `classificationProgressPct` (optional)

**BackfillSegment schema (backfill_segments collection):**
- `syncStatusId`, `walletAddress`, `networkId`, `segmentIndex`, `fromBlock`, `toBlock`, `status`, `progressPct`, `lastProcessedBlock`, `retryCount`, `errorMessage`, `startedAt`, `completedAt`, `updatedAt`
- Indexes:
  - unique `(syncStatusId, segmentIndex)`
  - `(syncStatusId, status, updatedAt)`
  - `(walletAddress, networkId, status)`

### 2. Incremental Sync (Hourly Cron)

Same two-phase split as backfill (ADR-020):

```
@Scheduled(fixedDelay=3_600_000)
  → find wallets WHERE backfillComplete=true
  → for each wallet×network (sync-executor):
      → Phase 1: fetch delta lastBlockSynced+1 → currentBlock → store raw in raw_transactions (PENDING)
      → update lastBlockSynced
  → Phase 2: RawTransactionClassifierJob picks up PENDING raw (by blockNumber/slot ASC)
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
