# Feature 13: Split Raw Fetch and Classification (T-031)

**Tasks:** T-031

**Branch:** `feature/13-split-raw-fetch-classification`

---

## Context

When classification produces wrong events (bug in classifier, wrong sessionWallets), the current flow requires full re-fetch from RPC. With split: raw is stored; we only re-run classification. This reduces operational cost and enables faster error recovery.

**Immediate balance on add wallet:** Out of scope for this task. Handle in a separate feature if needed.

---

## T-031 — Split ingestion into raw fetch and classification

- **Status:** Pending
- **Module(s):** ingestion (backfill, adapter, store), domain
- **Description:** Split the ingestion pipeline into two phases: (1) **Raw fetch** — fetch from RPC → save to `raw_transactions` with **full RPC response**; (2) **Classification** — read `raw_transactions` → classify → normalize → upsert to `economic_events`. Store the complete payload (EVM: full receipt; Solana: full transaction + sigInfo) so classifier changes never require re-fetch.
- **Doc refs:** 02-architecture (Data Flow 1 — Initial Wallet Backfill, Data Flow 2 — Incremental Sync), ADR-020 (split raw fetch vs classify — to be created)
- **DoD:** RawTransaction schema supports full payload; RawTransactionRepository with indexes; RawFetchSegmentProcessor (Phase 1); ClassificationProcessor (Phase 2); BackfillNetworkExecutor and IncrementalSyncJob use split flow; SyncStatus tracks rawFetchComplete and classificationComplete; classification retry does not re-fetch from RPC.
- **Dependencies:** T-009 (backfill), T-010 (incremental sync), T-030 (backfill refactoring)

---

## Data preservation (critical)

**Store the full RPC response.** Do not drop fields — this avoids reload on classifier changes.

| Network | Store |
|---------|-------|
| **EVM** | Full `eth_getTransactionReceipt` response (blockNumber, blockHash, logs, gasUsed, cumulativeGasUsed, status, transactionHash, etc.). Not just `logs`. |
| **Solana** | Full `getTransaction` result + full `sigInfo` from `getSignaturesForAddress` (signature, slot, blockTime, etc.). |

---

## Implementation Steps

### Step 1: RawTransaction schema + RawTransactionRepository + indexes

- **RawTransaction** (domain): Add per ADR-020:
  - `walletAddress` (String) — required; we fetch per wallet
  - `blockNumber` (Long) — EVM: from receipt; Solana: from slot (or use slot for Solana)
  - `slot` (Long) — Solana only; use for range queries
  - `classificationStatus` (enum: PENDING | COMPLETE | FAILED) — processor selects PENDING
  - `createdAt` (Instant) — debugging and retry ordering
  - Keep `rawData` as BSON Document for full payload
- **RawTransactionRepository** (domain): `MongoRepository<RawTransaction, String>`. Add:
  - `List<RawTransaction> findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(String walletAddress, String networkId, String status)`
  - `List<RawTransaction> findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(...)` — for segment-based read
- **Indexes:** Per ADR-020:
  - `(txHash, networkId)` UNIQUE — idempotency
  - `(walletAddress, networkId, blockNumber)` ASC — classifier read
  - `(walletAddress, networkId, classificationStatus)` — processor selects PENDING
  - For Solana: `(walletAddress, networkId, slot)` ASC
- **File:** `backend/src/main/java/com/walletradar/domain/RawTransaction.java`, `RawTransactionRepository.java` (new), `MongoConfig` or equivalent for index creation.

### Step 2: EvmNetworkAdapter — store full receipt (not just logs)

- Change `toRawTransaction` (or equivalent) to store the **entire receipt** in `rawData`, not only `logs`.
- Use `eth_getTransactionReceipt` response as-is: convert full JsonNode to BSON Document (blockNumber, blockHash, logs, gasUsed, cumulativeGasUsed, status, transactionHash, contractAddress, etc.).
- Set `RawTransaction.blockNumber` from receipt for indexing.
- Set `RawTransaction.walletAddress` — adapter receives `walletAddress` in `fetchTransactions`; pass it through.
- Set `RawTransaction.classificationStatus` = PENDING.
- Set `RawTransaction.createdAt` = Instant.now().
- **File:** `backend/src/main/java/com/walletradar/ingestion/adapter/evm/EvmNetworkAdapter.java`

**Note:** `fetchTransactions` returns `List<RawTransaction>`. The adapter does not persist; it builds RawTransaction objects. Persistence happens in RawFetchSegmentProcessor. So the adapter must set walletAddress, classificationStatus, createdAt when building each RawTransaction — so the caller can store them.

### Step 3: SolanaNetworkAdapter — ensure full payload stored

- Verify `rawData` contains full `getTransaction` result and full `sigInfo` (signature, slot, blockTime, err, memo, confirmationStatus, etc.).
- Set `RawTransaction.slot` at top level (from sigInfo.slot) for range queries.
- Set `RawTransaction.walletAddress`, `classificationStatus` = PENDING, `createdAt` = Instant.now().
- **File:** `backend/src/main/java/com/walletradar/ingestion/adapter/solana/SolanaNetworkAdapter.java`

### Step 4: RawFetchSegmentProcessor (new)

- New class: `com.walletradar.ingestion.backfill.RawFetchSegmentProcessor`.
- Responsibility: for one block-range segment, call `adapter.fetchTransactions` → upsert each `RawTransaction` to `RawTransactionRepository` (idempotent on txHash+networkId).
- Before save: ensure `RawTransaction.id` is set (e.g. `txHash + ":" + networkId`) for upsert behavior; adapter already sets walletAddress, classificationStatus, createdAt.
- Signature:

```java
void processSegment(String walletAddress, NetworkId networkId, NetworkAdapter adapter,
                    long segFromBlock, long segToBlock, int batchSize,
                    AtomicLong processedBlocks, long totalBlocks,
                    BackfillProgressCallback progressCallback);
```

- Injected: `RawTransactionRepository`, `NetworkAdapter` (via parameter).
- **File:** `backend/src/main/java/com/walletradar/ingestion/backfill/RawFetchSegmentProcessor.java`

### Step 5: ClassificationProcessor (new)

- New class: `com.walletradar.ingestion.backfill.ClassificationProcessor`.
- Responsibility: read `RawTransaction` from repository for (networkId, blockRange) → classify → normalize → inline swap price → PRICE_PENDING → upsert to IdempotentEventStore.
- Signature:

```java
void processSegment(String walletAddress, NetworkId networkId,
                    long segFromBlock, long segToBlock,
                    EstimatingBlockTimestampResolver estimator,
                    Map<LocalDate, BigDecimal> nativePriceCache, String nativeContract,
                    Set<String> sessionWallets,
                    AtomicLong processedBlocks, long totalBlocks,
                    BackfillProgressCallback progressCallback);
```

- Injected: `RawTransactionRepository`, `TxClassifierDispatcher`, `EconomicEventNormalizer`, `InlineSwapPriceEnricher`, `IdempotentEventStore`, `HistoricalPriceResolverChain`.
- **File:** `backend/src/main/java/com/walletradar/ingestion/backfill/ClassificationProcessor.java`

### Step 6: BackfillNetworkExecutor — use split flow

- **Phase 1:** Run `RawFetchSegmentProcessor` for each segment (sequential or parallel as today).
- **Phase 2:** Run `ClassificationProcessor` for each segment over the same block range (reads from raw_transactions).
- **Phase 3:** Deferred price resolution + RecalculateWalletRequestEvent (unchanged).
- **File:** `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillNetworkExecutor.java`

### Step 7: SyncStatus — add rawFetchComplete, classificationComplete

- Add `rawFetchComplete` (boolean) and `classificationComplete` (boolean) to `SyncStatus`.
- Set `rawFetchComplete=true` when Phase 1 (raw fetch) finishes for that wallet×network.
- Set `classificationComplete=true` when Phase 2 (classification) finishes.
- `backfillComplete` remains true only when both are true (or equivalent semantics).
- **File:** `backend/src/main/java/com/walletradar/domain/SyncStatus.java`, `SyncProgressTracker`, `BackfillNetworkExecutor`

### Step 8: Incremental sync — same split

- `IncrementalSyncJob` (or equivalent): Phase 1 raw fetch for new blocks → Phase 2 classification for same blocks.
- Reuse `RawFetchSegmentProcessor` and `ClassificationProcessor`.
- **File:** `backend/src/main/java/com/walletradar/ingestion/job/IncrementalSyncJob.java` (or wherever incremental sync is implemented)

---

## Acceptance criteria

- Raw transactions are stored with **full RPC payload** (EVM: full receipt; Solana: full transaction + sigInfo). No fields dropped.
- Raw fetch phase completes before classification phase; `rawFetchComplete` is set when Phase 1 finishes.
- Classification phase reads from `raw_transactions`; no RPC calls during classification.
- When classification fails or produces wrong events, re-running classification (e.g. via manual trigger or retry) does **not** re-fetch from RPC; it re-reads from `raw_transactions`.
- Idempotency: same `(txHash, networkId)` raw upsert overwrites; same economic event upsert remains idempotent.
- Backfill and incremental sync both use the split flow; `./gradlew :backend:test` passes.
- `docs/02-architecture.md` Data Flow 1 and Data Flow 2 updated to reflect Phase 1 (raw fetch) and Phase 2 (classification).

---

## Edge cases / tests

| Case | Description | In scope |
|------|-------------|----------|
| Classification failure retry | Re-run classification without re-fetch; read from raw_transactions | Yes |
| Idempotency | Same tx ingested twice (raw + events) → no duplicates | Yes |
| Empty block range | Raw fetch returns empty; classification returns empty; no errors | Yes |
| Classifier bug fix | Change classifier logic; re-run classification only; no RPC | Yes |
| Wrong sessionWallets | Add wallet; re-run classification with new sessionWallets; no RPC | Yes |
| Raw fetch failure | Phase 1 fails → status FAILED; retry re-fetches (Phase 1 only) | Yes |
| Partial raw + full classify | Some raw missing (e.g. RPC timeout) → classification skips or retries raw for that segment | Clarify: classification only processes available raw; missing raw may require re-fetch of that segment |

---

## Files to create

| File | Description |
|------|-------------|
| `backend/src/main/java/com/walletradar/domain/RawTransactionRepository.java` | MongoRepository with findByNetworkIdAndBlockNumberBetween |
| `backend/src/main/java/com/walletradar/ingestion/backfill/RawFetchSegmentProcessor.java` | Phase 1: fetch → save raw |
| `backend/src/main/java/com/walletradar/ingestion/backfill/ClassificationProcessor.java` | Phase 2: read raw → classify → upsert events |
| `backend/src/test/java/com/walletradar/ingestion/backfill/RawFetchSegmentProcessorTest.java` | Unit test |
| `backend/src/test/java/com/walletradar/ingestion/backfill/ClassificationProcessorTest.java` | Unit test |

## Files to update

| File | Change |
|------|--------|
| `backend/src/main/java/com/walletradar/domain/RawTransaction.java` | Add `blockNumber` (Long); ensure rawData holds full payload |
| `backend/src/main/java/com/walletradar/domain/SyncStatus.java` | Add `rawFetchComplete`, `classificationComplete` |
| `backend/src/main/java/com/walletradar/ingestion/adapter/evm/EvmNetworkAdapter.java` | Store full receipt in rawData |
| `backend/src/main/java/com/walletradar/ingestion/adapter/solana/SolanaNetworkAdapter.java` | Ensure full payload; set blockNumber |
| `backend/src/main/java/com/walletradar/ingestion/backfill/BackfillNetworkExecutor.java` | Use RawFetchSegmentProcessor + ClassificationProcessor; two-phase flow |
| `backend/src/main/java/com/walletradar/ingestion/job/SyncProgressTracker.java` | Support rawFetchComplete, classificationComplete |
| `backend/src/main/java/com/walletradar/ingestion/job/IncrementalSyncJob.java` | Same split flow (or create if not exists) |
| `docs/02-architecture.md` | Update Data Flow 1 and 2 with Phase 1 / Phase 2 |
| `MongoConfig` or index config | Add (networkId, blockNumber) index on raw_transactions |

---

## Risk notes

- **BackfillSegmentProcessor:** Removed (ADR-021). Replaced by RawFetchSegmentProcessor (Phase 1) + ClassificationProcessor (Phase 2).
- **Block number for Solana:** Solana uses slot; map to blockNumber for unified range query or use slot in index.
- **ADR-020:** Create ADR-020 to document the split architecture decision before or alongside implementation.
