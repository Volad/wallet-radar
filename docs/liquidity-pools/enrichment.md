# Liquidity Pools — On-Chain Enrichment

The enrichment layer is isolated from the read-model query path. `SessionLpQueryService` performs **zero RPC**; only `LpPositionRefreshJob` and the on-demand refresh endpoint call on-chain readers.

## Reader families

| Reader | Protocols | Live data |
|--------|-----------|-----------|
| `ConcentratedLiquidityReader` | Uniswap V3/V4, Pancake V3, Velodrome Slipstream, Aerodrome CL, Camelot, FusionX | tick range, in-range, token split, TVL, unclaimed fees (feeGrowth math) |
| `LiquidityDepthReader` | CL pools (same as above) | tickBitmap scan → `liquidityBins` histogram for distribution chart |
| `GmxPositionReader` | GMX GM/GLV | position value via `GmxProtocolSnapshotValuationService`; closed when balance = 0 |
| `PendleLpReader` | Pendle LPT | LPT balance + symbol (no underlying split) |
| `FungiblePoolReader` | Curve, Balancer, Aerodrome vAMM | LP balance × pool share via reserves |
| `PancakeMasterChefEnricher` | Pancake V3 staked positions | pending CAKE rewards merged into unclaimed fees |

GMX/Pendle/fungible positions return `NOT_APPLICABLE` for range and IL.

**Vault-style positions** (`correlationId` ending in `:vault`, non-numeric tokenId): CL reader rejects them (`supports()` returns false for non-numeric tokenId). Read-model still works from normalized txs + basis pools; no on-chain CL enrichment.

## RPC plumbing

- `EvmRpcClient.batchCall()` via `LpRpcSupport.callBatch()` — JSON-RPC batch with **chunking** (`MAX_BATCH_CHUNK = 50`) to stay within provider payload limits (413/429).
- When batch size ≥ `SKIP_INDIVIDUAL_FALLBACK_THRESHOLD` (10) and all batch attempts fail, individual-call fallback is **skipped** to avoid multi-minute blocking; caller receives empty results.
- `RpcEndpointRotator` — per-network endpoint failover with backoff/jitter.
- `EvmAbiSupport` — shared ABI encode/decode (`com.walletradar.ingestion.adapter.evm.abi`).

A single position refresh may trigger **many** RPC calls (CL read + depth scan); depth alone can send ~100 calls across multiple chunked batches.

### Concentrated liquidity reads

1. NFPM `positions(tokenId)` → ticks, liquidity, feeGrowthInsideLast, tokensOwed
2. Pool `slot0()` → current tick/price → in-range flag
3. `LpLiquidityAmountsSupport` → token0/token1 amounts from sqrtPriceX96
4. Unclaimed fees: `feeGrowthInside` math + `tokensOwed` (no `collect` callStatic)

Token0 < token1 ordering and decimals are fetched via RPC each read (no application-level cache).

### Liquidity depth reads

`LiquidityDepthReader` scans pool `tickBitmap` to build a histogram:

| Constant | Value | Purpose |
|----------|-------|---------|
| `MAX_SCAN_WORDS` | 50 | Bitmap words scanned (~12,800 ticks at spacing=1) |
| `MAX_BINS` | 60 | Max histogram bins returned |
| Display window | ±15% of position tick spread | Context around position range |

On depth read failure, `LpOnChainEnrichmentService` **carries over** prior `liquidityBins` from the existing snapshot rather than clearing them.

### Post-enrichment pricing (`applyMarks`)

After on-chain read, `LpPositionRefreshService.applyMarks()` prices snapshot TVL and unclaimed fees from `current_price_quotes` in MongoDB (including reward token symbols like CAKE).

## Refresh schedule

| Trigger | Behavior |
|---------|----------|
| `LpPositionRefreshJob` | `@Scheduled(fixedDelayString)` default **1 hour**; single-flight `AtomicBoolean`; refreshes OPEN positions only |
| `AccountingReplayCompletedEvent` | Session-scoped refresh after accounting replay completes |
| On-demand POST refresh | Debounce ~20s per `(session, correlationId)`; validates ownership; closed positions are skipped (not in open-context discovery) — returns cached read-model, no HTTP error |

There is **no** `ApplicationReadyEvent` warmup refresh for LP (unlike lending health-factor job).

## Failure isolation

Per-position try/catch: on RPC failure, **keep prior snapshot** and set `snapshotStale=true`. Shell snapshot created when enrichment returns no data. Never zero out or 500 the read path.

## Configuration

```yaml
walletradar:
  liquidity-pools:
    enabled: true
    refresh-interval-ms: 3600000
    on-demand-debounce-ms: 20000
    dust-threshold-usd: 10
```

Properties `stale-multiplier` and `depthIntervalMs` exist in `LiquidityPoolsProperties` but are **not read** by LP code — reserved for future use.

NFPM addresses are read from `protocol-registry.json` (read-only). Classification may reference `underlyingPositionManager` for vault-style pool correlation IDs (`lp-position:{network}:{underlying}:vault`).

## Out of scope (Phase 4)

- TraderJoe Liquidity Book (bin-based ERC-1155) — basis-only fallback until dedicated reader
- Full staked/gauge emission tracking as a distinct APR stream (partial: Pancake CAKE via MasterChef enricher)
