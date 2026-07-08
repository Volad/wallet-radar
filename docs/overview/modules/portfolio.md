# Portfolio module (`portfolio.application`)

## Purpose

Owns the **dashboard read model**: session portfolio snapshot, conservation gate evaluation at read time, session transaction history projection, and session/backfill status reads consumed by the BFF. Does **not** own normalization, linking, or replay writes (those stay in `application.*` pipeline jobs).

## Public port (`portfolio.application.port`)

| Port | Contract |
|------|----------|
| `SessionReadPort` | Session metadata and backfill-status views |
| `SessionDashboardReadPort` | Dashboard snapshot (`SessionDashboardView`) |
| `SessionTransactionsReadPort` | Paginated transaction history and rebuild metadata |

Boundary view types remain nested on the application services until a dedicated `port.view` split (A5).

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `on_chain_balances` | Live balance evidence (`OnChainBalanceRefreshService` refresh job) |

Portfolio GET paths are **read-only** over Mongo evidence produced by upstream pipeline stages.

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `session.application` | `AccountingUniverseService` | Scope member refs for universe-scoped reads |
| `application.linking.query` | `LinkingPendingStatusQuery` | Backfill status linking-pending signal |
| `costbasis.application.port` | `CexLiveBalancePort` | CEX umbrella balance clamp on dashboard |
| `costbasis` domain | `AssetLedgerPoint`, `OnChainBalance`, basis pools | Quantity / AVCO / liability evidence |
| `pricing` | `HistoricalPriceDocument`, `CurrentPriceQuoteDocument` | Mark-to-market |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `portfolio.application.port` | BFF-stable read interfaces |
| `portfolio.application` | Query services, `PortfolioConservationGate`, `OnChainBalanceRefreshService` |
| `api.portfolio` | REST DTO mappers for session portfolio GET paths |

## Allowed dependencies

- `canonical`, `domain`, `accounting.support`
- `session.application` (universe scope)
- `costbasis.domain` / `pricing` persistence for read joins
- `application.linking.query.LinkingPendingStatusQuery` (narrow query port; not job/pipeline)
- Other apps: prefer `application.*.port` for cross-app calls

## Extension seams

- `PortfolioConservationGate` — ADR-014 / ADR-034 NEC invariant at dashboard read time
- `api.portfolio` BFF mappers — zero-RPC GET guard in `ModuleBoundaryTest`

## Worked example

1. `PORTFOLIO_SNAPSHOT_REFRESH` materializes `on_chain_balances` and price quotes.
2. AVCO replay has written `asset_ledger_points` and basis pools.
3. Client `GET /api/v1/sessions/{id}/dashboard` → `SessionDashboardReadPort` joins balances, ledger tail, prices, runs conservation gate, returns `SessionDashboardView`.

## Microservice extraction

Deployable boundary: **portfolio-read** service exposing the three `port` interfaces over HTTP; BFF (`api`) maps port views to REST DTOs. No RPC on GET paths.
