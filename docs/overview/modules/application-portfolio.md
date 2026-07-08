# Application portfolio (`application.portfolio`)

## Purpose

Owns the **dashboard read model**: session portfolio snapshot, conservation gate at read time, transaction history projection, and session/backfill status views consumed by the BFF. Does **not** own normalization, linking, or replay writes.

> Alias: older doc `portfolio.md` — same module; this page is the Track A canonical name.

## Public port (`application.portfolio.port`)

| Port | Contract |
|------|----------|
| `SessionReadPort` | Session metadata and backfill-status views |
| `SessionDashboardReadPort` | Dashboard snapshot (`SessionDashboardView`) |
| `SessionTransactionsReadPort` | Paginated transaction history and rebuild metadata |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `on_chain_balances` | Live balance evidence (`OnChainBalanceRefreshService` refresh job) |

Portfolio GET paths are **read-only** over Mongo evidence produced by upstream pipeline stages.

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.session` | `AccountingUniverseService` | Scope member refs for universe-scoped reads |
| `application.linking.query` | `LinkingPendingStatusQuery` | Backfill status linking-pending signal |
| `application.costbasis.port` | `CexLiveBalancePort` | CEX umbrella balance clamp on dashboard |
| `application.costbasis.domain` | `AssetLedgerPoint`, `OnChainBalance`, basis pools | Quantity / AVCO / liability evidence |
| `application.pricing` | `HistoricalPriceDocument`, `CurrentPriceQuoteDocument` | Mark-to-market |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.portfolio.port` | BFF-stable read interfaces |
| `application.portfolio.application` | Query services, `PortfolioConservationGate`, `OnChainBalanceRefreshService` |
| `api.portfolio` | REST DTO mappers for session portfolio GET paths |

## Allowed dependencies

- `canonical`, `domain`, `application.costbasis.support`
- `application.session` (universe scope)
- `application.costbasis.domain` / `application.pricing` persistence for read joins
- `application.linking.query.LinkingPendingStatusQuery` (narrow query port)
- Other apps: `application.*.port` only

Forbidden: pipeline write jobs (`ModuleBoundaryTest.portfolio_read_model_must_not_depend_on_ingestion_job_or_pipeline_write`).

## Extension seams

- `PortfolioConservationGate` — ADR-014 / ADR-034 NEC invariant at dashboard read time
- `api.portfolio` BFF mappers — zero-RPC GET guard

## Worked example

1. `PORTFOLIO_SNAPSHOT_REFRESH` materializes `on_chain_balances` and price quotes.
2. AVCO replay has written `asset_ledger_points` and basis pools.
3. Client `GET /api/v1/sessions/{id}/dashboard` → `SessionDashboardReadPort` joins balances, ledger tail, prices, runs conservation gate, returns `SessionDashboardView`.

## Microservice extraction

Read-model service exposing the three ports over gRPC/REST. Subscribes to ledger/balance change events; no pipeline writes.
