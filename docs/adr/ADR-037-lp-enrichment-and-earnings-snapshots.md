# ADR-037 — LP on-chain enrichment and earnings snapshots

**Status:** Accepted  
**Date:** 2026-06-24  
**Inputs:** Liquidity Pools page implementation plan (read-model + enrichment + frontend)

## Context

LP cost basis and transaction history already exist in the pipeline (`lp_receipt_basis_pools`, `LP_*` normalized types, `lp-position:` correlation IDs). The LP page requires live metrics not persisted by normalization: tick range, in-range status, token split, TVL, unclaimed fees, impermanent loss, and daily earnings/APR history.

Classification/normalization must not change — the feature consumes existing pipeline output.

## Decision

### D1. Isolated enrichment package

- New package `com.walletradar.liquiditypools.enrichment` with per-family `LpPositionReader` implementations
- Uses existing `EvmRpcClient` + `RpcEndpointRotator`; shared `EvmAbiSupport` promoted to `com.walletradar.ingestion.adapter.evm.abi`
- Read-model (`SessionLpQueryService`) performs **zero RPC** — enrichment only via background job and on-demand refresh endpoint

### D2. Derived collections

| Collection | Key | Cleared on rebuild |
|------------|-----|-------------------|
| `lp_position_snapshots` | `correlationId` (upsert) | Yes |
| `lp_earning_points` | `correlationId:YYYY-MM-DD` (upsert) | **No** (irreplaceable wall-clock history) |

### D3. Refresh cadence

- `LpPositionRefreshJob`: hourly (configurable), single-flight, OPEN positions only
- On-demand: `POST /sessions/{id}/lp/positions/{correlationId}/refresh` with 20s debounce
- Failure: keep prior snapshot, set `snapshotStale=true`

### D4. Earnings derivation

```
cumulativeFees(t) = ledgerClaimed(≤t) + unclaimed(snapshot)
dailyEarning = cumulative(day) − cumulative(priorDay)
```

Never derive from TVL deltas. Claim add-back required on `LP_FEE_CLAIM` events.

### D5. Financial reconciliation

- Authoritative: basis/realized/claimed fees from AVCO/ledger only
- Two separate P&L numbers: economic (D_mkt anchor) vs accounting unrealized (B_avco anchor)
- LP-scoped realized == claimed fee income only (never withdrawals − deposits)
- Per-field precision: EXACT / ESTIMATE / NOT_APPLICABLE / UNAVAILABLE

### D6. API

- `GET /api/v1/sessions/{sessionId}/lp` — read-model
- `POST /api/v1/sessions/{sessionId}/lp/positions/{correlationId}/refresh` — on-demand enrichment

## Consequences

- No normalization/classification changes for LP feature scope; LP module consumes existing pipeline output
- `lp_earning_points` survives backend rebuilds (not in `reset-derived.sh`)
- Charts for pre-feature positions start "since tracking started" (first successful refresh; **no historical ledger seed**)
- Staked emissions and TraderJoe LB deferred to Phase 4 (partial: Pancake CAKE via `PancakeMasterChefEnricher`)

## Addendum (2026-06-27) — implementation refinements

The following behaviors were refined during implementation and are documented in detail in `docs/liquidity-pools/earnings.md` and `enrichment.md`:

| Area | Refinement |
|------|------------|
| **API scope** | `GET /lp?scope=active\|closed\|all` (default `active`) |
| **Refresh triggers** | Hourly schedule + `AccountingReplayCompletedEvent` (no `ApplicationReadyEvent` warmup) |
| **P&L openBase** | `depositedMarketUsd → hodlNow → AVCO`; price appreciation suppressed when `openBaseIsHodl` |
| **Entry vs Current** | Open: entry qty × current price; closed: AVCO cost basis |
| **Closed APR** | Positive-sum earning series preferred; `feesProxy = max(0, withdrawn − hodlNow)` fallback |
| **Velodrome vault close** | Stake-only positions (`stakeUnstakeCount > 0`, no LP_ENTRY) marked closed |
| **Liquidity depth** | `LiquidityDepthReader` with `MAX_SCAN_WORDS=50`, ±15% window, `liquidityBins` in snapshot |
| **RPC batching** | `LpRpcSupport` chunking (`MAX_BATCH_CHUNK=50`); skip individual fallback for large failed batches |
| **Vault correlationId** | Classification resolves `underlyingPositionManager` → `lp-position:{network}:{underlying}:vault` |
| **Ledger seed** | **Not implemented** — earning points created forward-only on refresh; no historical backfill from ledger |

## Related

- `SessionLpQueryService`, `LpOnChainEnrichmentService`, `LpPositionRefreshJob`
- `docs/liquidity-pools/overview.md`
- [ADR-018](ADR-018-lp-protocol-family-materialization.md) — LP family materialization (unchanged)
- [ADR-026](ADR-026-live-aave-v3-health-factor.md) — template for isolated snapshot refresh
