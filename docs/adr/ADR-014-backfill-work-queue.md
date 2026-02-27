# ADR-014: Backfill Work Queue and Worker Loops

**Date:** 2025  
**Status:** Accepted (updated: retry scheduler added)  
**Deciders:** Solution Architect

---

## Context

Backfill was scheduled by submitting a fixed set of `CompletableFuture` tasks to the backfill executor (one per wallet×network). When some jobs finished or failed, free threads did not automatically pick up other work:

- New work (e.g. two more wallets/networks added in DB as PENDING) was not scheduled until the next event or restart.
- FAILED jobs were only retried on restart.
- The executor could sit idle while there was still PENDING/FAILED work in the database.

The desired behaviour: as soon as a thread frees up (job completed or failed), it should take the next available job from a shared pool (PENDING and FAILED), so the runner does not idle.

---

## Decision

1. **Single shared work queue**
   - Introduce an in-memory **`BlockingQueue<BackfillWorkItem>`** (e.g. `LinkedBlockingQueue`) as the single source of work for backfill.
   - All work is represented as **`BackfillWorkItem(walletAddress, networkId)`**.

2. **Feeding the queue**
   - **On application ready:** Load all `SyncStatus` with status `PENDING`, `RUNNING`, or `FAILED` from the DB and enqueue corresponding work items. Then start the worker loops (see below). RUNNING is included so that after a restart we re-queue in-progress work (it will be re-run; idempotency and last-block tracking handle duplicates).
   - **On `WalletAddedEvent`:** For each (wallet, network) enqueue a `BackfillWorkItem`. PENDING is already persisted by `WalletBackfillService` before the event is published.

3. **Worker loops (no idle threads)**
   - Start a fixed number **N** of worker runnables on the **backfill executor** (N = backfill pool size, e.g. 4). Each worker runs a loop:
     - `BackfillWorkItem item = queue.take();`
     - Run `runBackfillForNetwork(item)` (existing logic).
     - Loop again. So as soon as one job finishes (success or failure), that thread takes the next item from the queue; no explicit "schedule next" callback needed.

4. **Reclassify and recalc**
   - Run reclassify + recalc when the queue is empty AND `inFlightItems` is empty AND no PENDING/RUNNING items exist in the DB.

5. **No change to executor configuration**
   - Keep `backfill-coordinator-executor` (single thread) and `backfill-executor` (N threads) as in **AsyncConfig**. The executor's threads are now used to run the N worker loops; the executor's internal queue is unused for backfill work (the application queue is the single backlog).

6. **Optional: configurable worker count**
   - Number of worker loops can be read from config (e.g. `walletradar.ingestion.backfill.worker-threads`) defaulting to the same as the backfill executor core pool size, so it can be tuned without code change.

7. **Automatic retry of FAILED items**
   - A `@Scheduled` method (`retryFailedBackfills`) runs every `retrySchedulerIntervalMs` (default 2 min) and queries `sync_status` for FAILED items.
   - Exponential backoff with jitter: `delay = min(baseDelay × 2^(retryCount−1), maxDelay)` + 25% jitter. Configured via `retry-base-delay-minutes` (default 2), `retry-max-delay-minutes` (default 60).
   - Items whose `nextRetryAfter` is still in the future are skipped.
   - After `maxRetries` (default 5) the item is marked `ABANDONED` and no longer retried automatically. User can re-add the wallet to reset.
   - `SyncStatus` tracks `retryCount` (int, incremented on each failure) and `nextRetryAfter` (Instant, computed by `SyncProgressTracker`). Both are reset on success or on manual re-add.

8. **In-flight deduplication**
   - A `ConcurrentHashMap.newKeySet()` (`inFlightItems`) tracks items currently in the queue or being processed, keyed by `walletAddress:networkId`.
   - `enqueueItemIfSupported` adds the key before enqueueing; if the key is already present, the item is skipped.
   - The worker loop removes the key in a `finally` block after processing completes (success or failure).

---

## Consequences

- **Positive:** Free threads immediately take the next job (PENDING or FAILED); new and failed work is processed without restart; behaviour is easy to reason about (one queue, N workers). Failed items are automatically retried with exponential backoff so workers stay busy.
- **Neutral:** Reclassify/recalc runs when the queue drains and DB confirms no active work; slightly more expensive check but reclassify is rare.
- **Risk:** Unbounded queue growth if work is enqueued faster than it is processed; acceptable for MVP and can be capped later.
- **Risk (Phase 2+):** `inFlightItems` is in-memory; with multiple instances it cannot prevent cross-instance duplicates. Phase 2 solution: use `status=RUNNING` in DB as a distributed lock.

---

## Configuration

```yaml
walletradar:
  ingestion:
    backfill:
      worker-threads: 4
      max-retries: 5
      retry-base-delay-minutes: 2
      retry-max-delay-minutes: 60
      retry-scheduler-interval-ms: 120000
```

---

## References

- **02-architecture.md** — ingestion, backfill executor, Data Flow 1.
- **BackfillJobRunner** — implementation of queue, worker loops, and retry scheduler.
- **SyncProgressTracker** — retry count increment and backoff calculation.
