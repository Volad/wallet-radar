# ADR-021: Classifier as Separate Process with Own Lifecycle

**Date:** 2026-02  
**Status:** Proposed  
**Deciders:** System Architect  
**Supersedes:** Extends ADR-020 (does not replace)

**Amendment (2026-02):** Deferred price + AVCO changed from Option C (event-driven) to **Option D (cron-based)**. See Section H below.

---

## Context

ADR-020 splits ingestion into Phase 1 (raw fetch) and Phase 2 (classification). The current implementation runs both phases **inline** within `BackfillNetworkExecutor`: raw fetch completes → classification runs block-range by block-range → deferred price + AVCO recalc.

The user proposes to **fully decouple** classification from Backfill:

- **Backfill** = ONLY raw fetch + store. No classification.
- **Classifier** = separate process with its own lifecycle, continuously processing PENDING raw transactions.

This document assesses the proposal, answers open questions, and recommends an approach.

---

## Decision Summary

| Aspect | Recommendation |
|--------|----------------|
| **Deferred price + AVCO** | **Option D (amended)** — `DeferredPriceResolutionJob` @Scheduled cron (2–5 min) finds wallets with PRICE_PENDING → resolveForWallet → RecalculateWalletRequestEvent. No ClassificationBatchCompleteEvent. |
| **Classifier lifecycle** | **Hybrid: Event-driven + Scheduled** — `RawFetchCompleteEvent` triggers immediate run; `@Scheduled` (1–2 min) catches stragglers and retries |
| **SyncStatus** | Keep `rawFetchComplete`; **deprecate** `classificationComplete` for "done" semantics; add optional `classificationProgressPct` for UX |
| **Estimator + nativePriceCache** | Classifier creates/calibrates per batch — **acceptable**; cost: 2 RPC calls per (wallet×network) batch |
| **InternalTransferReclassifier** | Run on **schedule** (e.g. every 5 min) when no backfill in progress; or after classifier batch if sessionWallets changed |

---

## Section A — Decisions & Assumptions

### A1. Backfill Scope

- Backfill = **raw fetch only**. Writes to `raw_transactions` with `classificationStatus=PENDING`.
- Backfill publishes `RawFetchCompleteEvent(walletAddress, networkId, lastBlockSynced)` when Phase 1 finishes.
- Backfill does **not** call `setClassificationComplete`, `DeferredPriceResolutionJob`, or `RecalculateWalletRequestEvent`.

### A2. Classifier Scope

- Classifier = **standalone job** that:
  - Queries `raw_transactions` WHERE `classificationStatus=PENDING` ORDER BY blockNumber/slot ASC
  - Processes in batches (e.g. 500–2000 raw per batch) per (walletAddress, networkId)
  - Creates `EstimatingBlockTimestampResolver` and calibrates per batch (2 RPC calls for EVM; Solana uses blockTime from raw)
  - Creates `nativePriceCache` per batch (in-memory, no persistence)
  - Classifies → normalizes → inline swap price → upsert to `economic_events`
  - Sets `classificationStatus=COMPLETE` or `FAILED` on each raw
  - Does **not** publish any event (deferred price + recalc via DeferredPriceResolutionJob cron; see Section H)

### A3. Deferred Price + AVCO (Question 1)

**Amended: Option D — Cron-based (replaces Option C)**

- **Classifier** publishes **no event** after classification.
- **DeferredPriceResolutionJob** runs `@Scheduled(fixedDelay = 2–5 min)`:
  - Query `findDistinctWalletAddressesByFlagCode(PRICE_PENDING)`
  - For each wallet: `resolveForWallet(wallet)` → `publishEvent(RecalculateWalletRequestEvent(wallet))`
- **Rationale (amendment):**
  - Simpler: removes ClassificationBatchCompleteEvent and ClassificationCompleteListener
  - Resilient: no event loss; DB is source of truth
  - Trade-off: up to 3 min latency for price resolution; acceptable for MVP

### A4. Classifier Lifecycle (Question 2)

**Recommended: Hybrid — Event-driven + Scheduled**

| Trigger | When | Purpose |
|---------|------|---------|
| `RawFetchCompleteEvent` | After Backfill Phase 1 | Immediate classification of newly fetched raw |
| `@Scheduled(fixedDelay = 90_000)` | Every 1.5 min | Catch stragglers, retry FAILED raw, process PENDING from incremental sync |

- **Queue-based** (Backfill publishes work items): Overkill for MVP; adds complexity. Revisit in Phase 2 if classifier becomes bottleneck.
- **Event-driven only**: If listener fails or event is lost, PENDING raw may never be processed. Scheduled fallback mitigates.

### A5. SyncStatus Semantics (Question 3)

| Field | Recommendation |
|-------|----------------|
| `rawFetchComplete` | **Keep.** Set by Backfill when Phase 1 done. |
| `classificationComplete` | **Deprecate for "done" semantics.** Classifier is continuous; there is no single "classification complete" moment. |
| `backfillComplete` | **= rawFetchComplete only.** For UX: "Backfill complete" = raw data captured. |
| `classificationProgressPct` | **Optional.** If UX needs "Classifying… X%", derive from: `COUNT(raw WHERE status=PENDING) / COUNT(raw) * 100` per wallet×network. Can be computed on-demand in API. |

**UX copy:** "Syncing… Raw fetch complete. Classifying transactions…" — user sees both phases; classification may lag.

### A6. EstimatingBlockTimestampResolver + nativePriceCache (Question 4)

**Acceptable for classifier to create/calibrate itself.**

| Component | Creation | Cost |
|-----------|----------|------|
| `EstimatingBlockTimestampResolver` | Per (wallet, network) batch | 2 RPC calls (anchor blocks) per batch |
| `nativePriceCache` | Per batch, in-memory | 0 RPC (uses HistoricalPriceResolverChain; CoinGecko cached) |

**Trade-off:** Classifier batches should be large enough (e.g. 500+ raw) to amortize 2 RPC calls. For small batches (e.g. incremental sync: 10 raw), 2 RPC calls may seem wasteful — but EVM anchor resolution is cheap (single `eth_getBlockByNumber` × 2). Acceptable for MVP.

**Alternative (future):** Store `(networkId, blockNumber) → timestamp` in a cache/collection for reuse across batches. Deferred to Phase 2 if profiling shows bottleneck.

### A7. InternalTransferReclassifier (Question 5)

**Recommended: Schedule when idle + optional event trigger**

| Trigger | When | Rationale |
|--------|------|-----------|
| `@Scheduled(fixedDelay = 300_000)` | Every 5 min, **only if** `backfillQueue.isEmpty() && inFlightItems.isEmpty()` | Reclassify runs when no backfill in progress; avoids race with new wallets being added |
| Optional: `WalletAddedEvent` | When new wallet added | Could trigger reclassify immediately so new wallet's internal transfers are correct sooner. Trade-off: may run while backfill still fetching; reclassify is idempotent, so safe. |

**Simpler approach:** Run reclassify on same schedule as classifier (e.g. every 5 min), after classifier run. Session wallets = all in sync_status. Reclassify is cheap (DB query + update).

---

## Section B — Cost-Efficient Architecture Diagram (ASCII)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         INGESTION PIPELINE (ADR-021)                             │
└─────────────────────────────────────────────────────────────────────────────────┘

  POST /wallets
       │
       ▼
  WalletAddedEvent ──────────────────────────────────────────────────────────────┐
       │                                                                         │
       ▼                                                                         │
  BackfillJobRunner (queue)                                                       │
       │                                                                         │
       ▼                                                                         │
  BackfillNetworkExecutor                                                        │
       │                                                                         │
       │  Phase 1 ONLY: Raw Fetch                                                │
       │  ┌─────────────────────────────────────────────────────────────┐        │
       │  │ RawFetchSegmentProcessor                                    │        │
       │  │   → RPC fetch → raw_transactions (PENDING)                  │        │
       │  │   → setRawFetchComplete()                                   │        │
       │  │   → publish RawFetchCompleteEvent(wallet, network)          │        │
       │  └─────────────────────────────────────────────────────────────┘        │
       │                                                                         │
       │  NO Phase 2, NO Phase 3 in Backfill                                     │
       │                                                                         │
       ▼                                                                         │
  sync_status: rawFetchComplete=true, backfillComplete=true                      │
                                                                                 │
  ═══════════════════════════════════════════════════════════════════════════════│
  SEPARATE: Classifier Job                                                       │
  ═══════════════════════════════════════════════════════════════════════════════│
                                                                                 │
  Triggers:                                                                      │
    1. RawFetchCompleteEvent ──────────────────────────────────────────────┐    │
    2. @Scheduled(90s) ────────────────────────────────────────────────────┤    │
                                                                           │    │
                                                                           ▼    │
  RawTransactionClassifierJob                                              │    │
       │                                                                   │    │
       │  SELECT raw WHERE classificationStatus=PENDING                     │    │
       │  ORDER BY (walletAddress, networkId, blockNumber)                  │    │
       │  BATCH (e.g. 1000 per run)                                        │    │
       │                                                                   │    │
       │  Per batch:                                                       │    │
       │    → EstimatingBlockTimestampResolver.calibrate() [2 RPC]          │    │
       │    → nativePriceCache (in-memory)                                 │    │
       │    → classify → normalize → inline swap → upsert economic_events   │    │
       │    → set raw.classificationStatus=COMPLETE                         │    │
       │    (no event published)                                           │    │
       │                                                                   │    │
       ▼                                                                   │    │
  DeferredPriceResolutionJob (@Scheduled 2–5 min)                            │    │
       │                                                                   │    │
       │  → findDistinctWalletAddressesByFlagCode(PRICE_PENDING)           │    │
       │  → for each wallet: resolveForWallet() → RecalculateWalletRequestEvent │
       │                                                                   │    │
       ▼                                                                   │    │
  AVCO recalc (async)                                                       │    │
                                                                           │    │
  InternalTransferReclassifier: @Scheduled(5min) when queue empty ──────────┘    │
```

---

## Section C — Module Breakdown

| Component | Package | Responsibility |
|-----------|---------|-----------------|
| **BackfillNetworkExecutor** | `ingestion.job.backfill` | Phase 1 only; remove Phase 2, Phase 3 |
| **RawFetchSegmentProcessor** | `ingestion.job.backfill` | Unchanged |
| **RawTransactionClassifierJob** | `ingestion.job` (new or rename) | Scheduled + event-driven; process PENDING raw in batches |
| **ClassificationProcessor** | `ingestion.job.backfill` | Logic extracted; called by ClassifierJob with batch context (estimator, cache, sessionWallets) |
| **DeferredPriceResolutionJob** | `ingestion.job` | @Scheduled cron: find PRICE_PENDING wallets → resolve → RecalculateWalletRequestEvent |
| **InternalTransferReclassifier** | `ingestion.classifier` | Triggered by schedule (when idle) or optionally by WalletAddedEvent |

**Events:**
- `RawFetchCompleteEvent(walletAddress, networkId, lastBlockSynced)` — classifier trigger
- ~~`ClassificationBatchCompleteEvent(walletAddress)`~~ — **removed** (amendment)

---

## Section D — Data Flow

### Initial Backfill (Decoupled)

1. **Backfill:** Raw fetch → store raw (PENDING) → setRawFetchComplete → publish RawFetchCompleteEvent → done
2. **Classifier:** (triggered by event or schedule) → read PENDING raw → classify → upsert events (no event published)
3. **DeferredPriceResolutionJob:** @Scheduled (2–5 min) → find PRICE_PENDING wallets → resolve → RecalculateWalletRequestEvent
4. **InternalTransferReclassifier:** Runs on schedule when queue empty

### Incremental Sync

- Incremental sync fetches new blocks → stores raw (PENDING)
- Classifier picks up PENDING on next run (event or schedule)
- Same downstream flow: deferred price → recalc

---

## Section E — Pros & Cons

### Pros

| Benefit | Description |
|---------|-------------|
| **Separation of concerns** | Backfill = RPC boundary only; Classifier = pure DB + business logic |
| **Independent scaling** | Classifier can run on different schedule/thread pool; backfill workers not blocked by classification |
| **Faster raw visibility** | User sees "Raw fetch complete" sooner; classification can lag without blocking |
| **Retry flexibility** | Classification retry does not re-fetch; classifier can retry FAILED raw independently |
| **Cost isolation** | RPC cost (Backfill) vs CPU/DB cost (Classifier) — easier to profile and tune |

### Cons

| Drawback | Mitigation |
|----------|------------|
| **Latency** | User may see PRICE_PENDING longer; classification runs async | Event-driven trigger minimizes; scheduled fallback catches stragglers |
| **Estimator RPC per batch** | 2 RPC calls per (wallet, network) batch | Amortized over 500+ raw; acceptable for MVP |
| **SyncStatus complexity** | classificationComplete deprecated | Use rawFetchComplete + optional progressPct |
| **Reclassify timing** | No longer "all backfills done" | Schedule when queue empty; or run after classifier |

### Risks

| Risk | Mitigation |
|------|------------|
| Event loss | Scheduled classifier (90s) catches PENDING raw |
| Classifier backlog | Batch size + parallel wallets; monitor PENDING count |
| sessionWallets staleness | Reclassify on schedule; sessionWallets = sync_status wallets |

---

## Section F — SyncStatus Field Changes

| Field | Before | After |
|-------|--------|-------|
| `rawFetchComplete` | Set by Backfill Phase 1 | Unchanged |
| `classificationComplete` | Set by Backfill Phase 2 | **Deprecated** — never set by Backfill; Classifier does not set "complete" (continuous) |
| `backfillComplete` | Both raw + classification done | **= rawFetchComplete** — Backfill "complete" when raw stored |
| `classificationProgressPct` | Optional | Optional; compute on-demand: `(COMPLETE count / total raw) * 100` per wallet×network |

---

## Section G — Implementation Checklist

1. Add `RawFetchCompleteEvent`
2. Remove from BackfillNetworkExecutor: Phase 2 (classificationProcessor), Phase 3 (deferred price, recalc event), setClassificationComplete
3. Add `RawTransactionClassifierJob`: @Scheduled(90s) + @EventListener(RawFetchCompleteEvent)
4. ~~Add `ClassificationCompleteListener`~~ — **replaced by:** DeferredPriceResolutionJob @Scheduled cron (see docs/tasks/14-deferred-price-cron-refactor.md)
5. Refactor InternalTransferReclassifier trigger: schedule when queue empty (or after classifier)
6. Update SyncProgressTracker: setComplete sets rawFetchComplete only; remove setClassificationComplete from Backfill path
7. Update docs/02-architecture.md Data Flow 1 and 2

---

## Section H — Amendment: Deferred Price → Cron (Option D)

**Date:** 2026-02

### Change Summary

| Before (Option C) | After (Option D) |
|------------------|------------------|
| Classifier publishes `ClassificationBatchCompleteEvent` after each batch | Classifier publishes **no event** |
| `ClassificationCompleteListener` @EventListener → deferred price + recalc | **Listener removed** |
| Event-driven, immediate | **Cron-driven** (`@Scheduled` 2–5 min) |

### New Flow

1. **RawTransactionClassifierJob** — processes PENDING raw → upsert economic_events (some PRICE_PENDING). **No event published.**
2. **DeferredPriceResolutionJob** — `@Scheduled(fixedDelay = 2–5 min)`:
   - Query: `findDistinctWalletAddressesByFlagCode(PRICE_PENDING)`
   - For each wallet: `resolveForWallet(wallet)` → `publishEvent(RecalculateWalletRequestEvent(wallet))`

### Rationale

- **Simplicity:** Removes `ClassificationBatchCompleteEvent` and `ClassificationCompleteListener`; single source of truth for "wallets needing price resolution" = DB query.
- **Resilience:** No event loss; cron always catches PRICE_PENDING.
- **Cost:** Slightly higher latency (up to 3 min) for price resolution; acceptable for MVP.

### Repository Requirement

`EconomicEventRepository` must support: **distinct wallet addresses where flagCode = PRICE_PENDING**.

- **Option A:** `findByFlagCode(flagCode)` with projection → dedupe in Java. Loads one doc per event; inefficient for many events.
- **Option B:** `@Aggregation` or `MongoTemplate.findDistinct()` — returns only distinct wallet strings. **Recommended.**

### Index

Add compound index on `economic_events`: `(flagCode, walletAddress)` for efficient distinct-by-flagCode query.

### Implementation

See **docs/tasks/14-deferred-price-cron-refactor.md** (T-033).

---

## References

- **ADR-020** — Split raw fetch vs classification (inline phases)
- **ADR-017** — Backfill package refactoring
- **ADR-016** — Backfill performance (estimator, parallel segments)
- **docs/tasks/13-split-raw-fetch-classification.md** — T-031
- **docs/tasks/14-deferred-price-cron-refactor.md** — T-033 (cron refactor)
