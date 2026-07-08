# Application pricing (`application.pricing`)

## Purpose

Owns **price resolution** (historical and current), the pricing data gate, and external price sources (Binance, Bybit, CoinGecko, DeFiLlama, ECB). Does **not** execute AVCO replay or write ledger rows.

## Public port (`application.pricing`)

| Port / type | Contract |
|-------------|----------|
| `PricingDataGateService` | Session-level pricing gate and pending-price queries |
| `PricingDataGateSnapshot` | Gate snapshot for orchestration |
| `PriceableFlowPolicy` | Which canonical flows require market quotes |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `historical_prices` | Cached historical USD quotes |
| `current_price_quotes` | Latest spot quotes for dashboard mark-to-market |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.session` | universe scope | Session-scoped pricing jobs |
| `domain` | `NormalizedTransaction` | Pending-price rows |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.pricing.application` | Jobs, gate, repair services, policy |
| `application.pricing.domain` | Price request models, asset catalog |
| `application.pricing.persistence` | Historical and current quote repositories |
| `application.pricing.resolver` | Swap-derived and external source adapters |

## Allowed dependencies

- `domain`
- `platform` (HTTP clients, rate limiting)
- `application.session` (universe scope)

Forbidden: `application.costbasis` replay internals, normalization write jobs.

## Extension seams

- `PriceSourceAdapter` SPI for external venues
- `SwapDerivedPriceResolver`, `StablecoinPriceResolver` event-local pricing chain

## Worked example

1. `LinkingCompletedEvent` enqueues pricing for session universe.
2. `PricingJob` resolves quotes and stamps `unitPriceUsd` on priceable flows.
3. `PricingCompletedEvent` triggers AVCO replay downstream.

## Microservice extraction

Pricing service with historical cache and gate API. Input: canonical transaction stream; output: priced rows + cache documents.
