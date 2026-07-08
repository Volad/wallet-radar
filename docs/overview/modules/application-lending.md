# Application lending (`application.lending`)

## Purpose

Owns **lending position monitoring** (Aave v3 health factor, market rates), lending receipt snapshots, and factual APY calculation. Does **not** own AVCO replay or normalization writes.

## Public port (`application.lending`)

| Port / type | Contract |
|-------------|----------|
| `LendingGroupRefreshStateService` | Read-only refresh state for BFF |
| `SessionLendingQueryService` | Session lending dashboard projection |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `lending_health_factor_snapshots` | On-chain health factor evidence |
| `lending_market_rate_snapshots` | Protocol market rate evidence |
| `lending_receipt_identities` | Receipt identity mapping |
| `lending_group_refresh_state` | Refresh orchestration state |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.session` | universe scope | Session-scoped refresh |
| `application.costbasis.support` | asset identity helpers | Receipt symbol resolution |
| `platform.networks` | live RPC adapters | Health factor collection |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.lending.application` | Query, refresh, APY calculators |
| `application.lending.persistence` | Snapshot documents and repositories |
| `application.lending.config` | Module properties |

## Allowed dependencies

- `domain`
- `platform.networks` (live RPC for health factor)
- `application.session`
- `application.costbasis.support` (identity helpers only)

Forbidden: normalization/linking write jobs, replay handlers.

## Extension seams

- `LendingAaveV3HealthCollector`, `LendingAaveV3MarketRateCollector` protocol collectors
- `LendingFactualApyCalculator` factual APY from ledger evidence

## Worked example

1. Portfolio refresh event triggers lending health-factor job.
2. Collector reads Aave v3 on-chain state via `platform.networks`.
3. BFF `GET /sessions/{id}/lending` reads snapshots only (zero RPC).

## Microservice extraction

Lending analytics service with scheduled on-chain collectors and snapshot store.
