# Application liquidity pools (`application.liquiditypools`)

## Purpose

Owns **LP position snapshots**, earning points, and pool depth cache. Refresh jobs run on pipeline events; the BFF reads materialized evidence only. Does **not** own AVCO replay or normalization.

## Public port (`application.liquiditypools`)

| Port / type | Contract |
|-------------|----------|
| `LpPositionRefreshService` | LP refresh orchestration |
| `SessionLpQueryService` | Session LP dashboard projection |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `lp_position_snapshots` | Position quantity and valuation evidence |
| `lp_earning_points` | Historical earning accrual points |
| `lp_pool_depth_cache` | Cached pool depth for enrichment |
| `lp_position_refresh_state` | Refresh orchestration state |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.session` | universe scope | Session-scoped refresh |
| `application.pricing` | price quotes | Mark-to-market on LP positions |
| `platform.networks` | RPC readers | On-chain LP enrichment |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.liquiditypools.application` | Refresh jobs, query services, views |
| `application.liquiditypools.enrichment` | On-chain LP readers and enrichers |
| `application.liquiditypools.persistence` | Snapshot documents and repositories |
| `application.liquiditypools.config` | Module properties |

## Allowed dependencies

- `domain`
- `platform.networks`
- `application.pricing`
- `application.session`

Forbidden: normalization/linking write jobs, replay handlers.

## Extension seams

- `LpPositionReader` SPI per protocol (GMX, Pendle, concentrated liquidity, etc.)
- `LpSnapshotEnricher` enrichment pipeline

## Worked example

1. `AccountingReplayCompletedEvent` triggers LP position refresh.
2. Enrichment reads on-chain state and prices positions via `application.pricing`.
3. BFF `GET /sessions/{id}/lp` reads snapshots only (zero RPC).

## Microservice extraction

LP analytics service with protocol reader plugins and snapshot store.
