# ADR-020: Split Raw Fetch vs Classification

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** System Architect

---

## Context

The current ingestion pipeline performs **fetch → classify → normalize → upsert** in a single pass within `BackfillSegmentProcessor`. This creates several problems:

1. **No data preservation:** EVM adapter extracts only `logs` and `blockNumber` from `eth_getTransactionReceipt`; the full receipt (blockHash, gasUsed, status, from, to, etc.) is discarded. Any future classifier or feature needing these fields requires re-fetching from RPC.
2. **Classification errors force full re-fetch:** If classification fails (e.g. classifier bug, unexpected tx shape), the only recovery is to re-run the entire backfill for that block range — expensive RPC calls and slow retries.
3. **Tight coupling:** Fetch logic and classification logic are intertwined; changes to one risk regressions in the other.
4. **Incremental sync duplicates work:** Both backfill and incremental sync repeat the same fetch→classify flow; there is no separation of "raw data capture" from "interpretation."

---

## Decision

Split ingestion into **two distinct phases** with a persistent `raw_transactions` collection as the boundary.

### Phase 1 — Raw Fetch & Store

**Components:** `BackfillJobRunner`, `BackfillNetworkExecutor`, **`RawFetchSegmentProcessor`** (new)

- Fetch transactions from RPC:
  - **EVM:** `eth_getLogs` + `eth_getTransactionReceipt` (unchanged RPC flow)
  - **Solana:** `getSignaturesForAddress` + `getTransaction` (unchanged RPC flow)
- **Store the FULL payload** in `raw_transactions` — do **not** drop any data received from RPC
- Update `SyncStatus` (progressPct, lastBlockSynced) for raw fetch phase
- No classification, no normalization, no economic_events writes

### Phase 2 — Classification Processor

**Component:** **`RawTransactionClassifierJob`** (or `ClassificationProcessor`) — new

- Read `RawTransaction` documents from DB, ordered by `blockNumber`/`slot` ASC per (walletAddress, networkId)
- Apply: `TxClassifierDispatcher` → `EconomicEventNormalizer` → `InlineSwapPriceEnricher`
- Upsert to `economic_events` via `IdempotentEventStore`
- On classification error: **retry without re-fetching from RPC** — raw data is already stored
- Update `classificationStatus` on `RawTransaction` to `COMPLETE` or `FAILED`

---

## Data Preservation Requirement (Critical)

### EVM — eth_getTransactionReceipt

**Current:** Only `logs` and `blockNumber` are stored.

**Required:** Store the **entire receipt** as `rawData`. The full receipt contains:

- `blockNumber`, `blockHash`, `transactionHash`, `transactionIndex`
- `from`, `to`, `cumulativeGasUsed`, `gasUsed`, `contractAddress`
- `logs`, `logsBloom`, `status`, `type`

Do **not** extract only logs. Persist the complete JSON-RPC response as received.

### Solana — getTransaction + getSignaturesForAddress

**Current:** Stores `signature`, `slot`, `blockTime`, `transaction` (full JSON). This is acceptable.

**Required:** Add any missing `sigInfo` fields (e.g. `err`, `confirmationStatus`) to `rawData` if available from `getSignaturesForAddress` response.

---

## RawTransaction Schema Additions

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `walletAddress` | String | Yes | Filtering; we fetch per wallet |
| `blockNumber` | Long | Yes (EVM) | Top-level for indexing/sorting without parsing rawData |
| `slot` | Long | Yes (Solana) | Top-level for indexing/sorting; Solana equivalent of blockNumber |
| `classificationStatus` | Enum | Yes | `PENDING` \| `COMPLETE` \| `FAILED` — processor selects PENDING |
| `createdAt` | Instant | Yes | Debugging and retry ordering |

Existing: `id`, `txHash`, `networkId`, `rawData`.

---

## Indexes for raw_transactions

| Index | Definition | Purpose |
|-------|------------|---------|
| **Idempotency** | `(txHash, networkId)` UNIQUE | Prevent duplicate raw docs on re-fetch |
| **Classifier read** | `(walletAddress, networkId, blockNumber)` ASC | Incremental read for classifier, ordered by block |
| **Processor selection** | `(walletAddress, networkId, classificationStatus)` | Processor selects `classificationStatus = PENDING` |

For Solana, use `slot` instead of `blockNumber` in the classifier-read index: `(walletAddress, networkId, slot)` ASC.

---

## SyncStatus Semantics (Extended)

Extend `SyncStatus` with two-phase tracking (one document per wallet×network):

| Field | Type | Purpose |
|-------|------|---------|
| `lastBlockSynced` | Long | Raw fetch progress (last block/slot stored) |
| `rawFetchComplete` | Boolean | Phase 1 done for this wallet×network |
| `classificationProgressPct` | Integer 0–100 | Optional; or derive from economic_events count vs raw_transactions count |
| `classificationComplete` | Boolean | Phase 2 done |

Existing fields (`status`, `progressPct`, `syncBannerMessage`, `backfillComplete`, etc.) remain; semantics may be refined to reflect raw vs classification phases.

---

## Incremental Sync

Same split applies:

1. **Fetch:** New blocks → save raw to `raw_transactions` → update `lastBlockSynced`
2. **Classify:** `RawTransactionClassifierJob` picks up new raw docs (PENDING) by `(walletAddress, networkId, blockNumber)` ASC

`lastBlockSynced` = raw fetch progress. The classifier reads raw by blockNumber/slot ASC, independent of incremental sync timing. Classifier can run on a schedule (e.g. every 1–2 min) or be triggered after each raw fetch batch.

---

## Component Summary

| Component | Responsibility |
|-----------|-----------------|
| **RawFetchSegmentProcessor** | Fetch one block-range segment from RPC; store full payload in raw_transactions; update SyncStatus (raw phase) |
| **BackfillNetworkExecutor** | Orchestrate raw fetch segments (parallel/sequential); when raw complete, trigger classifier; coordinate with DeferredPriceResolutionJob |
| **BackfillJobRunner** | Queue, worker loops, event listeners; delegates to BackfillNetworkExecutor |
| **RawTransactionClassifierJob** | Read PENDING raw_transactions; classify → normalize → enrich → upsert economic_events; set classificationStatus |

---

## Consequences

### Positive

- **Data preservation:** Full RPC payload stored; no re-fetch needed for classifier improvements or new features (e.g. gas analytics, status filtering)
- **Classification retry without RPC:** Failed classifications can be retried by re-running the classifier on stored raw data
- **Clear separation of concerns:** Fetch = RPC boundary; Classify = pure DB read + business logic
- **Cost efficiency:** RPC calls are the expensive part; storing full payload minimises future RPC dependency

### Trade-offs

- **Storage increase:** Full receipts are larger than logs-only; acceptable for MVP (MongoDB document size limit 16MB; typical receipt << 100KB)
- **Two-phase UX:** Users may see "Raw fetch complete, classifying…" — sync banner should reflect both phases
- **Migration:** Existing backfill flow must be refactored; `BackfillSegmentProcessor` is replaced by `RawFetchSegmentProcessor` for Phase 1; new classifier job for Phase 2

### Risks

- **Classifier must read from rawData:** TxClassifierDispatcher and downstream components must accept RawTransaction with full receipt/solana payload. Current classifiers expect logs; adapter layer may need to expose a unified view (e.g. `getLogs()` that extracts from full receipt).
- **Ordering:** Classifier must process raw_transactions in blockNumber/slot ASC order per wallet×network for correct AVCO. Index and query design must enforce this.

---

## References

- **ADR-016** — Backfill performance optimizations (T-OPT-1 through T-OPT-6); raw fetch phase retains batch RPC, timestamp estimation
- **ADR-017** — Backfill package refactoring; RawFetchSegmentProcessor replaces BackfillSegmentProcessor for Phase 1
- **ADR-021** — Classifier as separate process (extends this ADR; decouples classification from Backfill entirely)
- **docs/01-domain.md** — INV-02: Raw transactions are immutable
- **docs/02-architecture.md** — Data Flow 1 updated to reflect two-phase pipeline
