# ADR-016: Backfill Performance Optimizations

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

Initial wallet backfill for 8 EVM networks was taking **60–120 minutes** per wallet due to:

- **Per-block RPC calls for timestamps:** Each block required a separate `eth_getBlockByNumber` call to obtain the timestamp used for AVCO ordering. A typical backfill touched ~500 blocks, costing ~500 extra RPC round-trips.
- **Sequential HTTP requests:** Each `eth_getLogs` filter and each `eth_getTransactionReceipt` was sent as a separate HTTP request, adding round-trip latency.
- **Inline price resolution:** Every non-stablecoin event triggered a synchronous CoinGecko API call during ingestion. CoinGecko's free-tier rate limit (45 req/min) became the dominant bottleneck.
- **Conservative batch sizes and scan windows:** Default batch-block-size (2000) and large default scan windows resulted in many small batches over unnecessarily large block ranges.
- **Sequential block-range processing:** Each (wallet, network) job processed its entire block range on a single thread.

These delays made the product unusable for real-time onboarding — users waited over an hour before seeing any portfolio data.

---

## Decision

Six targeted optimizations (T-OPT-1 through T-OPT-6) were implemented, designed to be independent and individually revertable.

### T-OPT-1: Block Timestamp Estimation

**`EstimatingBlockTimestampResolver`** uses linear interpolation between 2 anchor blocks (first and last in the scan range) to estimate timestamps for all intermediate blocks. This replaces ~500 per-block `eth_getBlockByNumber` RPC calls with exactly 2 calls per network.

- Anchor blocks are fetched once at the start of backfill for each network.
- Accuracy: ±1–2 seconds for networks with consistent block times (ETH, L2s). Sufficient for AVCO ordering which only requires `blockTimestamp ASC` — sub-second precision is not needed.
- Incremental sync continues to use actual block timestamps (fetched from transaction receipts) for new blocks.

### T-OPT-2: Increased Batch Block Size

Default `batch-block-size` values increased to reduce the number of `eth_getLogs` round-trips:

| Network | Before | After |
|---------|--------|-------|
| ETH (L1) | 2 000 | 10 000 |
| L2 (ARB, OP, BASE, SCROLL, LINEA, ZKSYNC) | 5 000 | 20 000 |
| Others | 2 000 | 5 000 |

`MAX_BATCH_BLOCK_SIZE` cap raised from 10 000 to **50 000** in `EvmBatchBlockSizeResolver`. Validation rules from ADR-011 still apply (≤0 or >cap → fallback to default + warning log).

### T-OPT-3: Reduced Default Scan Window

All `window-blocks` values halved to approximately 1 year of history (was ~2 years). This cuts the total number of blocks scanned in half for new wallets:

| Network | Before | After |
|---------|--------|-------|
| ETH | 5 256 000 | 2 628 000 |
| ARB | 252 000 000 | 126 000 000 |
| OP / BASE | 63 072 000 | 31 536 000 |
| SCROLL | 12 614 400 | 6 307 200 |
| LINEA | 25 228 800 | 12 614 400 |
| ZKSYNC | 50 457 600 | 25 228 800 |

### T-OPT-4: JSON-RPC Batch Requests

`EvmRpcClient.batchCall()` sends multiple JSON-RPC calls in a single HTTP POST (JSON array). Two usage sites:

1. **`eth_getLogs`**: 2 filter calls (ERC-20 Transfer + native) combined into 1 HTTP request.
2. **`eth_getTransactionReceipt`**: N receipt calls batched into chunks of 50 → `ceil(N/50)` HTTP requests instead of N.

Fallback: if the RPC provider returns an error for the batch, the caller retries with sequential individual calls. This handles providers that don't support or have limits on batch size.

### T-OPT-5: Deferred Price Resolution

During backfill (Phase 1), non-stablecoin events are stored with a `PRICE_PENDING` flag instead of resolving prices inline. After all transactions are fetched:

- **`DeferredPriceResolutionJob.resolveForWallet()`** queries events with `PRICE_PENDING`, groups them by `(assetContract, date)`, and makes one CoinGecko historical price call per unique (contract, date) pair.
- This reduces CoinGecko calls from O(events) to O(unique asset×day pairs) — typically 10–50× fewer calls.
- After prices are resolved, `AvcoEngine.recalculateForWallet()` runs to compute cost basis.

### T-OPT-6: Parallel Block Range Segments

The block range for each (wallet, network) job is split into **N parallel segments** (default 4, configurable via `walletradar.ingestion.backfill.parallel-segments`). Each segment is processed as a `CompletableFuture` on the shared `backfill-executor` pool.

- Small ranges (< 10 000 blocks) are processed sequentially to avoid overhead.
- Segments are merged after completion; event ordering is guaranteed by `blockTimestamp ASC` at AVCO recalc time, not at ingestion time.

---

## Consequences

### Positive

- **Expected 10–20× speedup**: backfill reduced from 60–120 min to an estimated 3–5 min per wallet (8 networks).
- **Fewer RPC calls**: timestamp estimation alone saves ~500 calls per network; batch requests further halve HTTP round-trips.
- **CoinGecko rate limit no longer the bottleneck**: deferred resolution with grouping reduces calls from hundreds/thousands to tens.
- **Each optimization is independent**: can be toggled or reverted individually via configuration or feature flags.

### Trade-offs

- **Timestamp estimation accuracy (T-OPT-1):** Estimated timestamps may differ from actual by ±1–2 seconds. This is acceptable for AVCO ordering but means `blockTimestamp` on events ingested during backfill is approximate. Incremental sync uses actual timestamps. If exact timestamps are needed retroactively, a re-sync would be required.
- **Deferred pricing UX (T-OPT-5):** During Phase 1, events lack price data. Users see transactions without USD values until Phase 2 completes. The sync progress banner communicates this ("Resolving prices…"). Portfolio value and P&L are unavailable until Phase 2 + AVCO recalc finish.
- **Larger batch sizes (T-OPT-2):** Some RPC providers may reject or timeout on large block ranges. The existing retry + fallback-to-smaller-batch logic in `EvmNetworkAdapter` mitigates this.
- **Parallel segments thread contention (T-OPT-6):** Segments share the `backfill-executor` pool with other wallet/network jobs. Under high load (many wallets backfilling simultaneously), parallelism benefit diminishes. Acceptable for MVP single-user scenario.

---

## Configuration

```yaml
walletradar:
  ingestion:
    backfill:
      parallel-segments: 4        # T-OPT-6: segments per (wallet, network) job
    network:
      ETH:
        batch-block-size: 10000   # T-OPT-2 (was 2000)
        window-blocks: 2628000    # T-OPT-3 (was 5256000)
      ARB:
        batch-block-size: 20000
        window-blocks: 126000000
      # ... other networks follow same pattern
```

---

## References

- **ADR-011** — Configurable EVM batch block size per network (semantics and validation rules unchanged).
- **ADR-014** — Backfill work queue and worker loops (parallelism builds on existing executor pool).
- **docs/02-architecture.md** — Data Flow 1 updated to reflect two-phase backfill.
- **`EstimatingBlockTimestampResolver`** — Timestamp interpolation implementation.
- **`DeferredPriceResolutionJob`** — Post-backfill price resolution implementation.
- **`EvmRpcClient.batchCall()`** — JSON-RPC batch request implementation.
