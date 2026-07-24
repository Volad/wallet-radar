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
- `LendingJupiterLendMarketRateCollector` — Solana `LendingMarketRateReader` (Jupiter Lend Borrow API supply/borrow rates), so Net APY renders for receipt-less Jupiter Lend (see ADR-076)
- `LendingActiveMarketDiscoveryService` has **two** discovery sources: the EVM receipt/debt-token scan over `on_chain_balances`, and `LendingReceiptLessActiveMarketSource` which emits a SUPPLY/BORROW `ActiveMarket` per leg of the freshest `lending_live_position_snapshots` row (so receipt-less positions — native SOL collateral + synthetic debt, invisible to `on_chain_balances` — are refreshed too; see ADR-076 §C). Markets are keyed `protocol:NETWORK:ACCOUNT-POOL` + `cycleStateAsset` underlying to match the built position lookup.
- `LendingFactualApyCalculator` factual APY from ledger evidence. The APR **exposure denominator** is the **time-weighted average principal** over `[cycle start, cycle end]` (running principal integrated across deposit/withdraw and borrow/repay deltas, divided by duration) — NOT the first deposit, which overstates the rate for multi-deposit cycles. The income **cost-basis** stays the total principal deposited. Single-deposit cycles are unchanged (time-weighted principal of one deposit at cycle start equals that deposit).

## Worked example

1. Portfolio refresh event triggers lending health-factor job.
2. Collector reads Aave v3 on-chain state via `platform.networks`.
3. BFF `GET /sessions/{id}/lending` reads snapshots only (zero RPC).

## Microservice extraction

Lending analytics service with scheduled on-chain collectors and snapshot store.
