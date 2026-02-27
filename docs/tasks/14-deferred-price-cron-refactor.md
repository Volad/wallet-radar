# Feature 14: Deferred Price Resolution — Cron-Based Refactor

**Tasks:** T-033  
**Branch:** `feature/14-deferred-price-cron-refactor`  
**Depends on:** ADR-021 (classifier as separate process)

---

## Context

Current flow (ADR-021 Option C): `RawTransactionClassifierJob` publishes `ClassificationBatchCompleteEvent` after each batch → `ClassificationCompleteListener` runs `DeferredPriceResolutionJob.resolveForWallet()` + `RecalculateWalletRequestEvent`.

**Problem:** Event-driven coupling adds complexity; listener is the only consumer of `ClassificationBatchCompleteEvent`. A cron-based approach simplifies the pipeline and removes the event entirely.

---

## T-033 — Refactor Deferred Price to Cron Job

- **Status:** Pending
- **Module(s):** ingestion (job), domain
- **Description:** Remove `ClassificationCompleteListener`; make `DeferredPriceResolutionJob` a `@Scheduled` cron job that finds all wallets with PRICE_PENDING events, resolves prices per wallet, and publishes `RecalculateWalletRequestEvent` for each.
- **Doc refs:** 02-architecture (Data Flow 1), ADR-021 (Section H amendment)
- **DoD:** ClassificationCompleteListener deleted; ClassificationBatchCompleteEvent removed; RawTransactionClassifierJob no longer publishes it; DeferredPriceResolutionJob runs on schedule; EconomicEventRepository has method to find distinct wallets with PRICE_PENDING; all tests pass.

---

## Implementation Tasks

### Task 1: EconomicEventRepository — distinct wallets by flagCode

- **File:** `EconomicEventRepository.java`
- **Add:** Method to return distinct `walletAddress` values where `flagCode == PRICE_PENDING`.
- **Options:**
  - **A) @Aggregation** — `$match` + `$group` by walletAddress. Returns `List<String>` via projection. Requires custom result mapping or `AggregationResults`.
  - **B) MongoTemplate.findDistinct** — Custom repository fragment `EconomicEventRepositoryCustom` + `EconomicEventRepositoryImpl` using `mongoTemplate.findDistinct(Query, "walletAddress", EconomicEvent.class, String.class)`.
- **Recommendation:** Option B — simpler, no aggregation pipeline parsing. Worker may use Option A if preferred (single repository, no custom impl).
- **Index:** Add `@CompoundIndex` on `EconomicEvent`: `(flagCode, walletAddress)` — supports both `findByWalletAddressAndFlagCode` (existing) and `findDistinct` by flagCode. Check if `(walletAddress, flagCode)` already covers; for distinct by flagCode we need `flagCode` first. Add: `@CompoundIndex(name = "flagCode_wallet", def = "{'flagCode': 1, 'walletAddress': 1}")` on EconomicEvent.

### Task 2: DeferredPriceResolutionJob — add @Scheduled cron

- **File:** `DeferredPriceResolutionJob.java`
- **Changes:**
  - Inject `ApplicationEventPublisher`.
  - Add `@Scheduled(fixedDelayString = "${walletradar.ingestion.deferred-price.schedule-interval-ms:180000}")` — default 3 min (2–5 min range).
  - New method `runScheduled()`: call `findDistinctWalletAddressesByFlagCode(FlagCode.PRICE_PENDING)` → for each wallet: `resolveForWallet(wallet)` → `publishEvent(RecalculateWalletRequestEvent(wallet))`.
  - Keep `resolveForWallet(String)` public for reuse and testing.
- **Config:** Add `walletradar.ingestion.deferred-price.schedule-interval-ms: 180000` to `application.yml` (3 min default).

### Task 3: Delete ClassificationCompleteListener

- **Delete:** `ClassificationCompleteListener.java`
- **Delete:** `ClassificationCompleteListenerTest.java`

### Task 4: RawTransactionClassifierJob — remove event publishing

- **File:** `RawTransactionClassifierJob.java`
- **Remove:** `ApplicationEventPublisher` dependency.
- **Remove:** `publishEvent(ClassificationBatchCompleteEvent)` after each batch (line ~143).
- **Remove:** import of `ClassificationBatchCompleteEvent`.
- **Update:** `RawTransactionClassifierJobTest` — remove `capturedEvents` and assertions on `ClassificationBatchCompleteEvent`; remove `ApplicationEventPublisher` from constructor; verify `processBatch` is still called.

### Task 5: Remove ClassificationBatchCompleteEvent

- **Delete:** `ClassificationBatchCompleteEvent.java`

### Task 6: Config and SchedulerConfig

- **File:** `application.yml`
- **Add:** under `walletradar.ingestion`:
  ```yaml
  deferred-price:
    schedule-interval-ms: 180000   # 3 min (2–5 min acceptable)
  ```
- **File:** `SchedulerConfig.java` — no change; DeferredPriceResolutionJob uses default scheduler pool (2 threads). Ensure `@EnableScheduling` is present.

### Task 7: DeferredPriceResolutionJob tests

- **File:** `DeferredPriceResolutionJobTest.java`
- **Add:** Test for `runScheduled()`: mock `findDistinctWalletAddressesByFlagCode` to return `["0xA", "0xB"]`; verify `resolveForWallet` called for each; verify `RecalculateWalletRequestEvent` published for each.
- **Add:** Test for empty list — no resolve, no events.

---

## Acceptance Criteria

- [ ] ClassificationCompleteListener removed; no references remain.
- [ ] ClassificationBatchCompleteEvent removed; no references remain.
- [ ] RawTransactionClassifierJob no longer publishes any event after classification.
- [ ] DeferredPriceResolutionJob runs on schedule (configurable 2–5 min).
- [ ] Cron job finds distinct wallets with PRICE_PENDING, resolves prices, publishes RecalculateWalletRequestEvent per wallet.
- [ ] EconomicEventRepository has efficient query for distinct wallets by flagCode (index + method).
- [ ] All tests pass: `./gradlew :backend:test`

---

## Files to Create

| File | Description |
|------|-------------|
| `EconomicEventRepositoryCustom.java` (optional) | Interface for custom distinct method |
| `EconomicEventRepositoryImpl.java` (optional) | Impl using MongoTemplate.findDistinct |

*If using @Aggregation, no new files.*

---

## Files to Update

| File | Change |
|------|--------|
| `EconomicEvent.java` | Add `@CompoundIndex(flagCode, walletAddress)` |
| `EconomicEventRepository.java` | Add `findDistinctWalletAddressesByFlagCode(FlagCode)` |
| `DeferredPriceResolutionJob.java` | Add @Scheduled, ApplicationEventPublisher, runScheduled() |
| `RawTransactionClassifierJob.java` | Remove ApplicationEventPublisher, remove publishEvent |
| `application.yml` | Add deferred-price.schedule-interval-ms |
| `DeferredPriceResolutionJobTest.java` | Add runScheduled tests |
| `RawTransactionClassifierJobTest.java` | Remove event capture, simplify |

---

## Files to Delete

| File | Reason |
|------|--------|
| `ClassificationCompleteListener.java` | Replaced by cron |
| `ClassificationCompleteListenerTest.java` | Listener removed |
| `ClassificationBatchCompleteEvent.java` | No consumers |

---

## Data Flow (After)

```
RawTransactionClassifierJob (@Scheduled 90s + RawFetchCompleteEvent)
  → processBatch() → upsert economic_events (some PRICE_PENDING)
  → NO event published

DeferredPriceResolutionJob (@Scheduled 3 min)
  → findDistinctWalletAddressesByFlagCode(PRICE_PENDING)
  → for each wallet: resolveForWallet() → publish RecalculateWalletRequestEvent
  → AvcoEngine.recalculateForWallet() (async)
```

---

## Latency Trade-off

| Before | After |
|--------|-------|
| Immediate after each classifier batch | Up to 3 min (configurable) |
| Event-driven, no delay | Cron-driven, bounded delay |

**Mitigation:** 2–5 min is acceptable for MVP; PRICE_PENDING events are non-blocking for read path. User sees "resolving…" until next cron run.
