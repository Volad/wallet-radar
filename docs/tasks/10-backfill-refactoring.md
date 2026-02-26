# Feature 10: Backfill Package Refactoring (SRP)

**Tasks:** T-030

---

## T-030 — Extract BackfillJobRunner into backfill subpackage

- **Status:** Completed. `BackfillJobRunner`, `BackfillNetworkExecutor`, `BackfillSegmentProcessor`, and `BackfillProgressCallback` now reside in `com.walletradar.ingestion.backfill`.
- **Module(s):** ingestion (backfill)
- **Description:** Refactor `BackfillJobRunner` (458 lines) by extracting it into three focused classes inside a new `com.walletradar.ingestion.backfill` package. The current class mixes orchestration (queue, worker loops, event listeners, retry), per-network execution (block range, calibrate estimator, sequential vs parallel segments, deferred price resolution), and segment processing (fetch → classify → normalize → upsert). Split into `BackfillJobRunner` (orchestrator), `BackfillNetworkExecutor` (per-network execution), and `BackfillSegmentProcessor` (single segment processing). Add `BackfillProgressCallback` functional interface.
- **Doc refs:** 02-architecture (Spring Boot Packages, Data Flow 1), ADR-014 (backfill work queue), ADR-016 (backfill performance optimizations), ADR-017 (this refactoring decision)
- **DoD:** All three classes implemented; `BackfillProgressCallback` interface; all existing `BackfillJobRunnerTest` assertions pass after refactoring; new unit tests for `BackfillNetworkExecutor` and `BackfillSegmentProcessor`; no behavioral changes.
- **Dependencies:** T-009 (existing backfill must work first)

---

## Implementation Steps

### Step 1: Create package and functional interface

Create package `com.walletradar.ingestion.backfill` and add `BackfillProgressCallback`:

```java
package com.walletradar.ingestion.backfill;

@FunctionalInterface
public interface BackfillProgressCallback {
    void reportProgress(int progressPct, long lastBlockSynced, String message);
}
```

### Step 2: Extract BackfillSegmentProcessor

Create `BackfillSegmentProcessor` — responsible for processing one block-range segment: loop over blocks, `adapter.fetchTransactions`, classify, normalize, set `PRICE_PENDING`, `idempotentEventStore.upsert`, progress reporting.

**Class signature:**

```java
package com.walletradar.ingestion.backfill;

@Component
public class BackfillSegmentProcessor {

    void processSegment(String walletAddress, NetworkId networkId,
                        NetworkAdapter adapter, EstimatingBlockTimestampResolver estimator,
                        long segFromBlock, long segToBlock,
                        Map<LocalDate, BigDecimal> nativePriceCache, String nativeContract,
                        Set<String> sessionWallets, int batchSize,
                        AtomicLong processedBlocks, long totalBlocks,
                        BackfillProgressCallback progressCallback);
}
```

**Moves from `BackfillJobRunner`:**
- `processSegment(...)` method body
- `getBlockNumberFromRaw(...)` helper
- `resolveNativePrice(...)` helper

**Injected dependencies (constructor):**
- `TxClassifier`
- `EconomicEventNormalizer`
- `IdempotentEventStore`
- `StablecoinRegistry`
- `HistoricalPriceResolver`
- `FlagService`

### Step 3: Extract BackfillNetworkExecutor

Create `BackfillNetworkExecutor` — runs backfill for one (wallet, network): resolve block range, calibrate `EstimatingBlockTimestampResolver`, decide sequential vs parallel segments, call `BackfillSegmentProcessor` for each segment, then run deferred price resolution and publish `RecalculateWalletRequestEvent`.

**Class signature:**

```java
package com.walletradar.ingestion.backfill;

@Component
public class BackfillNetworkExecutor {

    void runBackfillForNetwork(String walletAddress, NetworkId networkId,
                               Set<String> sessionWallets);
}
```

**Moves from `BackfillJobRunner`:**
- `runBackfillForNetwork(...)` method body
- `findAdapter(...)` helper
- `findResolver(...)` helper
- `getWindowBlocksForNetwork(...)` helper
- `getFallbackAvgBlockTime(...)` helper

**Injected dependencies (constructor):**
- `List<NetworkAdapter>` adapters
- `List<EvmBlockTimestampResolver>` resolvers
- `BackfillSegmentProcessor`
- `DeferredPriceResolutionJob`
- `SyncProgressTracker`
- `IngestionNetworkProperties`
- `BackfillProperties`
- `ApplicationEventPublisher`
- `Executor backfillExecutor` (for parallel segments)

### Step 4: Slim down BackfillJobRunner

Move `BackfillJobRunner` from `com.walletradar.ingestion.job` to `com.walletradar.ingestion.backfill`. It keeps only orchestration logic.

**Keeps:**
- `BlockingQueue`, `inFlightItems`, `ConcurrentHashMap` dedup
- Worker loop (`scheduleBackfillWork`)
- `enqueueWork(...)` method
- `@EventListener(WalletAddedEvent)` — `onWalletAdded`
- `@EventListener(ApplicationReadyEvent)` — `onApplicationReady`
- `@Scheduled` — `retryFailedBackfills`
- Reclassify trigger logic (`InternalTransferReclassifier` call + `RecalculateWalletRequestEvent`)

**Delegates to:**
- `BackfillNetworkExecutor.runBackfillForNetwork(...)` for actual per-network work

**Injected dependencies (constructor):**
- `BackfillNetworkExecutor`
- `SyncProgressTracker`
- `SyncStatusRepository`
- `InternalTransferReclassifier`
- `ApplicationEventPublisher`
- `BackfillProperties`
- `Executor backfillExecutor`
- `Executor backfillCoordinatorExecutor`

### Step 5: Update consumer references

| File | Change |
|------|--------|
| `BackfillJobRunnerTest.java` | Update import to `ingestion.backfill.BackfillJobRunner`; mock `BackfillNetworkExecutor` instead of lower-level dependencies; verify orchestration behavior only |
| `WalletAndSyncIntegrationTest.java` | Update import to `ingestion.backfill.BackfillJobRunner` |
| ArchUnit tests (if any) | Adjust package references if they check `ingestion.job` explicitly for `BackfillJobRunner` |

### Step 6: Create new tests

| Test class | Location | Scope |
|------------|----------|-------|
| `BackfillNetworkExecutorTest` | `src/test/java/com/walletradar/ingestion/backfill/` | Mock `BackfillSegmentProcessor`, verify block range resolution, sequential/parallel decision, deferred price call, event publishing |
| `BackfillSegmentProcessorTest` | `src/test/java/com/walletradar/ingestion/backfill/` | Mock adapter/classifier/normalizer/store, verify fetch-classify-normalize-upsert loop, progress callback invocation, native price resolution |

---

## Files to create

| File | Description |
|------|-------------|
| `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillProgressCallback.java` | Functional interface |
| `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillSegmentProcessor.java` | Segment processing |
| `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillNetworkExecutor.java` | Per-network execution |
| `backend/src/test/java/com/walletradar/ingestion/backfill/BackfillNetworkExecutorTest.java` | Unit test |
| `backend/src/test/java/com/walletradar/ingestion/backfill/BackfillSegmentProcessorTest.java` | Unit test |

## Files to move

| From | To |
|------|----|
| `backend/src/main/java/com/walletradar/ingestion/job/BackfillJobRunner.java` | `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillJobRunner.java` |

## Files to update (imports / references)

| File | Change |
|------|--------|
| `backend/src/test/java/com/walletradar/ingestion/job/BackfillJobRunnerTest.java` | Move to `ingestion/backfill/`, update imports, mock `BackfillNetworkExecutor` |
| `backend/src/test/java/com/walletradar/ingestion/adapter/WalletAndSyncIntegrationTest.java` | Update import for `BackfillJobRunner` |
| `docs/02-architecture.md` | Updated package tree and Data Flow 1 (already done as part of this task set) |

## Classes that stay in `ingestion.job` (no change)

Backfill classes have been moved to `ingestion.backfill`; the following remain in `ingestion.job`:

- `IncrementalSyncJob`
- `CurrentBalancePollJob`
- `DeferredPriceResolutionJob`
- `SyncProgressTracker`
- `WalletBackfillService`
- `WalletSyncStatusService`

---

## Acceptance criteria

- All existing `BackfillJobRunnerTest` assertions pass after refactoring (same behavioral contracts).
- `BackfillJobRunner` class size ≤ 200 lines; `BackfillNetworkExecutor` ≤ 200 lines; `BackfillSegmentProcessor` ≤ 200 lines.
- New unit tests for `BackfillNetworkExecutor` verify: block range resolution, sequential path for small ranges (< 10 000 blocks), parallel path for large ranges, deferred price resolution call, `RecalculateWalletRequestEvent` publishing, `setComplete` / `setFailed` status transitions.
- New unit tests for `BackfillSegmentProcessor` verify: fetch → classify → normalize → upsert loop, `PRICE_PENDING` flag on non-stablecoin events, native price resolution and caching, progress callback invocation with correct percentage, `getBlockNumberFromRaw` parsing.
- `./gradlew :backend:test` passes with zero failures.
- No behavioral changes: backfill produces identical `economic_events`, `sync_status` transitions, and AVCO recalc results as before.
- Package structure matches `docs/02-architecture.md` Spring Boot Packages section.

## Edge cases / tests

- **Empty block range (fromBlock == toBlock):** `BackfillNetworkExecutor` completes immediately, calls deferred price resolution and recalc with zero events.
- **Single segment (small range < 10 000 blocks):** `BackfillNetworkExecutor` processes sequentially, does not spawn parallel futures.
- **Segment processing failure mid-range:** exception propagates to `BackfillNetworkExecutor` which sets status FAILED; `retryFailedBackfills` re-enqueues.
- **Progress callback with zero totalBlocks:** no division-by-zero; progress reports 100%.
