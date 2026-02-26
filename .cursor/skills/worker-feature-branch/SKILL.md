---
name: worker-feature-branch
description: Implements WalletRadar by feature using a feature-branch workflow. Creates a new git branch per feature, implements all tasks and acceptance criteria from the feature file in docs/tasks, then runs tests. Use when the worker implements functionality from docs/tasks or when the user asks for feature-branch implementation.
---

# Worker: Feature-branch implementation

Implement **one feature per branch**. Do not mix features in a single branch.

## Workflow (per feature)

1. **Create branch** — from current `main` (or integration branch):  
   `git checkout -b feature/<branch-slug>`  
   Use the branch name from the table below for the chosen feature.

2. **Read feature file** — open `docs/tasks/<feature-file>.md` for that feature. It contains task(s), acceptance criteria, and edge cases.

3. **Implement** — complete all tasks listed in the feature file. Follow `docs/01-domain.md`, `docs/02-architecture.md`, `docs/03-accounting.md`, `docs/04-api.md`. Use **Gradle** for backend (not Maven); repository is **monorepo** (backend + frontend). Satisfy DoD: code + unit tests + integration tests where applicable.

4. **Verify** — run tests; ensure acceptance criteria and edge cases from the feature file are covered.

5. **Commit** — single logical commit per feature (or minimal commits), e.g.  
   `feat(<scope>): <short description>`  
   Push branch when done. Do not merge; merging is a separate step.

6. **Next feature** — switch back to base branch, pull if needed, then create the next feature branch. Implement in **dependency order** (foundation first, then 01 → 09).

## Feature → branch and task file

| Feature | Branch name | Task file |
|---------|-------------|-----------|
| Foundation | `feature/00-foundation` | `docs/tasks/00-foundation.md` |
| Ingestion core | `feature/00-ingestion-core` | `docs/tasks/00-ingestion-core.md` |
| Cost basis engine | `feature/00-cost-basis-engine` | `docs/tasks/00-cost-basis-engine.md` |
| Pricing | `feature/00-pricing` | `docs/tasks/00-pricing.md` |
| Add wallet + backfill | `feature/01-add-wallet-backfill` | `docs/tasks/01-add-wallet-backfill.md` |
| Wallet sync status | `feature/02-wallet-sync-status` | `docs/tasks/02-wallet-sync-status.md` |
| Incremental sync | `feature/03-incremental-sync` | `docs/tasks/03-incremental-sync.md` |
| Current balance | `feature/04-current-balance` | `docs/tasks/04-current-balance.md` |
| Override + recalc | `feature/05-override-recalc` | `docs/tasks/05-override-recalc.md` |
| Manual compensating transaction | `feature/06-manual-compensating-transaction` | `docs/tasks/06-manual-compensating-transaction.md` |
| Reconciliation + GET /assets | `feature/07-reconciliation-get-assets` | `docs/tasks/07-reconciliation-get-assets.md` |
| Transaction history | `feature/08-transaction-history` | `docs/tasks/08-transaction-history.md` |
| Portfolio snapshots + charts | `feature/09-portfolio-snapshots-charts` | `docs/tasks/09-portfolio-snapshots-charts.md` |
| Split raw fetch + classification | `feature/13-split-raw-fetch-classification` | `docs/tasks/13-split-raw-fetch-classification.md` |

## Dependency order

Implement in this order so dependencies are ready:

1. `feature/00-foundation` (T-001, T-002, T-003)
2. `feature/00-ingestion-core` (T-004 … T-008)
3. `feature/00-pricing` (T-019, T-020)
4. `feature/00-cost-basis-engine` (T-015, T-016)
5. `feature/01-add-wallet-backfill` (T-009, T-023)
6. `feature/02-wallet-sync-status` (T-011, T-023 partial)
7. `feature/03-incremental-sync` (T-010, T-023 partial)
8. `feature/04-current-balance` (T-012, T-013, T-014)
9. `feature/05-override-recalc` (T-017, T-028)
10. `feature/06-manual-compensating-transaction` (T-018, T-028 partial)
11. `feature/07-reconciliation-get-assets` (T-024, T-029)
12. `feature/08-transaction-history` (T-025)
13. `feature/09-portfolio-snapshots-charts` (T-021, T-022, T-026, T-027)
14. `feature/13-split-raw-fetch-classification` (T-031) — after backfill and incremental sync

## Clarifications

- If the feature file or domain/architecture is unclear, **ask the system-architect** before implementing.
- One feature = one branch. Do not put two feature files (e.g. 01 and 02) in the same branch.
- Branch names use lowercase and hyphens; no spaces or slashes inside the slug.
