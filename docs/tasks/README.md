# WalletRadar — Task List for Worker

The **worker** subagent implements from this folder. Each file contains **one feature** (or one infrastructure block) with its **implementation tasks** and **acceptance criteria**.

## File layout

| File | Content | Task IDs |
|------|---------|----------|
| **00-foundation.md** | Domain model, MongoDB, config (caches, async, scheduler) | T-001, T-002, T-003 |
| **00-ingestion-core.md** | Network adapters, classifiers, normalizer, IdempotentEventStore | T-004 … T-008 |
| **00-cost-basis-engine.md** | AvcoEngine, CrossWalletAvcoAggregatorService | T-015, T-016 |
| **00-pricing.md** | Historical + spot price resolvers | T-019, T-020 |
| **01-add-wallet-backfill.md** | Add wallet + backfill | T-009, T-023 |
| **02-wallet-sync-status.md** | GET wallet sync status | T-011, T-023 (partial) |
| **03-incremental-sync.md** | Hourly sync + POST /sync/refresh | T-010, T-023 (partial) |
| **04-current-balance.md** | Current balance poll (10 min) + manual refresh | T-012, T-013, T-014 |
| **05-override-recalc.md** | Override + recalc job status | T-017, T-028 |
| **06-manual-compensating-transaction.md** | Manual compensating transaction | T-018, T-028 (partial) |
| **07-reconciliation-get-assets.md** | Reconciliation + GET /assets | T-024, T-029 |
| **08-transaction-history.md** | GET transaction history (paginated) | T-025 |
| **09-portfolio-snapshots-charts.md** | Snapshots + charts | T-021, T-022, T-026, T-027 |
| **10-backfill-refactoring.md** | Backfill package refactoring (SRP) | T-030 |
| **11-inline-swap-price.md** | Inline price from swap when one leg is stablecoin (ADR-018) | T-031 |
| **12-heuristic-swap-detection.md** | Heuristic swap detection: one asset out + one asset in (ADR-019) | T-032 |
| **13-split-raw-fetch-classification.md** | Split raw fetch vs classification (ADR-020) | T-031 |
| **14-deferred-price-cron-refactor.md** | Deferred price as cron job; remove ClassificationCompleteListener (ADR-021 amendment) | T-033 |

**Dependency order:** Implement 00-* first, then 01 → 02 → … → 09. Task 10 can be done any time after T-009 is complete. Task 11 (T-031) depends on T-009 (backfill). Task 12 (T-032) depends on T-009 and works with T-031 (inline swap price). See **mvp-implementation-tasks.md** for full dependency graph.

## Build and repository

- **Build:** **Gradle** for backend (not Maven). Use `./gradlew` at project root; e.g. `./gradlew :backend:test`, `./gradlew :backend:bootRun`.
- **Monorepo:** Backend and frontend in one repo; backend (e.g. root or `backend/`), frontend (e.g. `frontend/`). See **ADR-010** and `.cursor/rules/build-and-repo.mdc`.

## Workflow

1. Read the task(s) and acceptance criteria from the relevant file (e.g. `01-add-wallet-backfill.md`).
2. Follow `docs/01-domain.md`, `docs/02-architecture.md`, `docs/03-accounting.md`, `docs/04-api.md`.
3. Satisfy DoD: implementation + unit tests + integration tests where applicable.
4. If unclear, ask the system-architect. See **risk-notes.md** for open decisions.
