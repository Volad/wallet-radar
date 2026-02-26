# ADR-017: Backfill Package Refactoring

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

`BackfillJobRunner` in `com.walletradar.ingestion.job` grew to **458 lines** and mixes three distinct responsibilities:

1. **Orchestration** — `BlockingQueue` management, `inFlightItems` deduplication, worker loops, `@EventListener` handlers (`WalletAddedEvent`, `ApplicationReadyEvent`), `@Scheduled retryFailedBackfills`, reclassify trigger.
2. **Per-network execution** — resolving block range, calibrating `EstimatingBlockTimestampResolver`, deciding sequential vs parallel segment processing, invoking deferred price resolution, publishing `RecalculateWalletRequestEvent`.
3. **Segment processing** — iterating over a block range, calling `adapter.fetchTransactions`, running classifier and normalizer, setting `PRICE_PENDING` flags, upserting via `IdempotentEventStore`, reporting progress.

This violates the **Single Responsibility Principle** and creates several problems:

- **Testability:** Unit testing orchestration requires mocking segment-level dependencies (adapter, classifier, normalizer, store); testing segment processing requires setting up queue infrastructure. Tests are brittle and tightly coupled.
- **Readability:** A 458-line class with 6+ injected dependencies and mixed abstraction levels is hard to navigate and review.
- **Evolution risk:** Adding features (e.g. per-network retry policy, segment-level metrics, different processing strategies) requires modifying a single large class.

---

## Decision

Extract `BackfillJobRunner` into **three classes** within a new flat package `com.walletradar.ingestion.backfill`:

| Class | Responsibility | Estimated Size |
|-------|----------------|----------------|
| **BackfillJobRunner** | Orchestrator: queue, worker loops, event listeners, `@Scheduled` retry, reclassify trigger. Delegates per-network work to `BackfillNetworkExecutor`. | ~150 lines |
| **BackfillNetworkExecutor** | Runs backfill for one (wallet, network): block range resolution, estimator calibration, sequential vs parallel segments, deferred price resolution, recalc event, status transitions. Delegates segment work to `BackfillSegmentProcessor`. | ~150 lines |
| **BackfillSegmentProcessor** | Processes one block-range segment: fetch → classify → normalize → `PRICE_PENDING` → upsert. Reports progress via `BackfillProgressCallback`. Owns `resolveNativePrice` and `getBlockNumberFromRaw` helpers. | ~150 lines |

A **`BackfillProgressCallback`** functional interface decouples segment progress reporting from the `SyncProgressTracker` implementation.

**Package move:** `BackfillJobRunner` moves from `ingestion.job` to `ingestion.backfill`. All other job classes (`IncrementalSyncJob`, `CurrentBalancePollJob`, `DeferredPriceResolutionJob`, `SyncProgressTracker`, `WalletBackfillService`, `WalletSyncStatusService`) stay in `ingestion.job`.

---

## Consequences

### Positive

- **Better testability:** Each class can be unit-tested independently with focused mocks. `BackfillJobRunnerTest` mocks `BackfillNetworkExecutor`; `BackfillNetworkExecutorTest` mocks `BackfillSegmentProcessor`.
- **SRP compliance:** Each class has a single reason to change — orchestration policy, execution strategy, or processing logic.
- **Smaller classes:** ~150 lines each vs 458 lines combined. Easier to review, navigate, and onboard.
- **Extensibility:** Per-network retry, metrics, or alternative processing strategies can be added to the appropriate class without touching others.

### Trade-offs

- **Minimal breaking changes:** `BackfillJobRunner` keeps the same class name (Spring `@Component`). Only the package changes (`ingestion.job` → `ingestion.backfill`), requiring import updates in tests and any direct references. **The move has been completed:** `BackfillJobRunner`, `BackfillNetworkExecutor`, `BackfillSegmentProcessor`, and `BackfillProgressCallback` now reside in `com.walletradar.ingestion.backfill`. `WalletBackfillService`, `DeferredPriceResolutionJob`, `IncrementalSyncJob`, `CurrentBalancePollJob`, `SyncProgressTracker`, and `WalletSyncStatusService` remain in `ingestion.job`.
- **Slight indirection:** One additional method call hop (`BackfillJobRunner` → `BackfillNetworkExecutor` → `BackfillSegmentProcessor`). Negligible runtime cost.
- **Test migration:** Existing `BackfillJobRunnerTest` must be updated to mock `BackfillNetworkExecutor` instead of lower-level dependencies. Two new test classes are required.

### Risks

- **Regressions:** Mitigated by requiring all existing test assertions to pass post-refactoring. No behavioral changes — identical `economic_events`, `sync_status` transitions, and AVCO results.

---

## References

- **ADR-014** — Backfill work queue and worker loops (orchestration logic retained in `BackfillJobRunner`).
- **ADR-016** — Backfill performance optimizations (parallel segments and deferred pricing logic moves to `BackfillNetworkExecutor`).
- **docs/02-architecture.md** — Spring Boot Packages section updated to reflect new `ingestion.backfill` subpackage.
- **docs/tasks/10-backfill-refactoring.md** — Implementation task T-030.
