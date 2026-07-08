# Application cost basis (`application.costbasis`)

## Purpose

Owns **AVCO replay**, immutable `asset_ledger_points`, auxiliary basis books (counterparty pools, LP receipt pools, borrow liabilities), and portfolio-snapshot refresh triggers. Consumes priced, linked, `CONFIRMED` canonical rows only. Must **not** fetch raw chain data or call CEX APIs directly.

## Public port (`application.costbasis.port`)

| Port | Contract |
|------|----------|
| `AssetLedgerReadPort` | Session/family ledger history for move-basis BFF |
| `CexLiveBalancePort` | Umbrella CEX balance clamp (implemented by `application.cex` adapter) |
| `BybitLiveBalanceReadPort` | Legacy narrow read; migrates behind `CexLiveBalancePort` |
| `OnChainBalanceRefresher` | Triggers balance refresh job (portfolio stage) |

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `asset_ledger_points` | Immutable replay truth per asset/family |
| `counterparty_basis_pools` | Per-counterparty carry pools |
| `lp_receipt_basis_pools` | LP receipt bucket state |
| `borrow_liabilities` | Open borrow positions |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| `application.normalization` / query | confirmed canonical rows | Replay input |
| `application.linking` | linkage metadata on rows | Continuity / corridor plans |
| `application.pricing` | flow `unitPriceUsd` | Mark-to-market at replay |
| `canonical.correlation` | prefix constants | Bybit corridor matching |
| `application.costbasis.support` | asset identity, leverage hooks | Family equivalence |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.costbasis.port` | BFF- and portfolio-stable reads |
| `application.costbasis.application.replay` | `AvcoReplayService`, handlers, engine |
| `application.costbasis.application.replay.handler` | `ReplayHandler` per canonical type family |
| `application.costbasis.application` | Query services, snapshot refresh job |
| `application.costbasis.domain` | Ledger entities and repositories |
| `application.costbasis.support` | Shared accounting identity and family helpers |

## Allowed dependencies

- `canonical`
- `domain` (normalized tx read models via query services, not write repos in other apps)
- `application.costbasis.support`
- `application.cex.port` / `CexLiveBalancePort` only (no `application.cex.acquisition`)
- `application.pricing` (price gate)
- `application.session` (universe scope)

Forbidden: `ingestion`, `integration` (enforced by `ModuleBoundaryTest`).

## Extension seams

- `ReplayHandler` registry (A5) — register handler per canonical type cluster
- `LeverageBorrowReplayHook`, `CounterpartyBasisPoolReplayHook` — family-specific hooks
- Conservation guards (`CorridorBasisConservationGuard`, `NativePoolReconciliationGate`)

## Worked example

1. `PricingCompletedEvent` starts `AvcoReplayService` for session universe.
2. Confirmed rows replay in deterministic order; `TransferReplayHandler` applies carry for Bybit corridor.
3. `asset_ledger_points` append-only; dashboard reads tail via `AssetLedgerReadPort`.

## Microservice extraction

Accounting replay service owns ledger collections. Input: priced canonical event stream + universe snapshot. Output: ledger append stream. No normalization or linking writes.
