# API BFF (`api`)

## Purpose

**Backend-for-frontend** layer: REST controllers, Jakarta validation, request/response DTOs, and mappers from application read ports. Translates HTTP to port calls and back. Must **not** embed pipeline business rules, live RPC on GET paths, or direct Mongo writes outside delegated commands.

## Public port (`api`)

Not a port owner — consumes `*.application.port` interfaces.

| Area | Controllers / mappers |
|------|----------------------|
| Session & wallets | `SessionController`, `WalletController` |
| Portfolio dashboard | `SessionPortfolioBffMapper` → `portfolio.application.port` |
| Asset ledger (move basis) | `AssetLedgerController`, `AssetLedgerBffMapper` → `costbasis.application.port` |
| Lending / LP | `LendingController`, `LpController` |
| Auth | `AuthController` |
| Admin integration | `AdminIntegrationPipelineController` |

## Owned collections (write owner)

None. Commands delegate to `session`, `application.backfill`, `application.cex`, etc.

## Read ports consumed

| Source module | Port | HTTP surface |
|---------------|------|--------------|
| `portfolio.application.port` | `SessionReadPort`, `SessionDashboardReadPort`, `SessionTransactionsReadPort` | `/api/v1/sessions/{id}/*` |
| `costbasis.application.port` | `AssetLedgerReadPort` | `/api/v1/sessions/{id}/assets/{family}` |
| `session.application` | session commands | POST wallets, settings |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `api.controller` | REST endpoints, thin delegation |
| `api.dto` | Request/response records |
| `api.validation` | Custom Jakarta constraints |
| `api.portfolio` | Portfolio BFF mappers |
| `api.costbasis` | Asset ledger BFF mappers |

## Allowed dependencies

- `application.*.port`, `portfolio.application.port`, `costbasis.application.port`
- `session.application` command services
- `api.dto`, `api.validation`
- **Not** `platform.networks` RPC adapters on GET portfolio paths (`ModuleBoundaryTest`)

## Extension seams

- New GET features: add port on application module first, then BFF mapper + DTO
- `ValidationExceptionHandler` — single 400 surface for constraint violations

## Worked example

1. `GET /api/v1/sessions/{id}/dashboard` hits `SessionController`.
2. Controller calls `SessionDashboardReadPort.findDashboard(sessionId)`.
3. `SessionPortfolioBffMapper.toDashboardResponse` maps `SessionDashboardView` → `SessionDashboardResponse`.
4. No RPC calls; data from `on_chain_balances`, ledger tail, cached quotes.

## Microservice extraction

BFF remains the edge API gateway. Extracted services expose gRPC/REST ports; BFF retains DTO shaping and auth only.
