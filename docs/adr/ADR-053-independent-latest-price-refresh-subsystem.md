# ADR-053 — Independent Latest-Price Refresh Subsystem

**Status:** Accepted  
**Date:** 2026-07-12  
**Theme:** Pricing — latest-price refresh / current quotes

---

## Context

Before this ADR, the application had no reliable mechanism for keeping `current_price_quotes` up to date:

- `CurrentPriceQuoteRefreshService` ran at startup and periodically, but was tightly coupled to the normalization pipeline (required `NormalizedTransactionSource`, used `PriceExternalSourceOrchestrator`) and mixed pricing concerns with pipeline event processing.
- Dzengi equity prices were not refreshed at all because `DzengiEquityPriceSourceAdapter` rejected `ON_CHAIN` transaction sources.
- `SessionDashboardQueryService` fell back to stale historical prices when no current quote was available.
- LP page TVL used entry-time prices rather than current prices, leading to incorrect mark-to-market P&L.
- There was no concept of a "tracked asset registry" — no systematic inventory of which symbols need live prices.

The goal was:
1. Keep `current_price_quotes` fresh every ~30 minutes from two independent sources (Bybit spot tickers, Dzengi 24hr tickers).
2. Do so in a single bulk HTTP call per source — not per-transaction, not per-symbol, and completely independent of RPC calls.
3. Ensure a single authoritative read path for all consumers (dashboard, LP, lending pages).
4. Never write to `historical_prices` — AVCO computations remain unaffected.

---

## Decision

Implement an **independent latest-price refresh subsystem** in `com.walletradar.application.pricing.latest` consisting of:

### 1. Global Asset Registry (`tracked_price_assets`)

`TrackedAssetRegistryMaintainer` rebuilds a `tracked_price_assets` collection before each refresh cycle by unioning canonical symbols from:
- `on_chain_balances` (non-zero qty)
- `bybit_live_balances` (non-zero umbrellaQty)
- `dzengi_live_balances` (non-zero umbrellaQty; equity symbols override CRYPTO kind — see §3)
- `lp_position_snapshots` (open positions — token0, token1, unclaimed fees)

Asset `Kind` values: `CRYPTO`, `EQUITY`, `STABLECOIN`, `UNKNOWN`.  
Stale entries (not seen for `registryPruneTtlDays` days) are pruned automatically.

### 2. Provider SPI (`LatestPriceProvider`)

```java
interface LatestPriceProvider {
    PriceSource source();
    int priority();
    Map<String, NormalizedLatestQuote> fetchAll(Map<String, TrackedPriceAssetDocument.Kind> wantedSymbolsWithKind);
}
```

Two implementations:
- `BybitTickerLatestPriceProvider` (priority 1): single GET `/v5/market/tickers?category=spot` → maps `XXXUSDT`/`XXXUSDC` → canonical `XXX`. Skips EQUITY-kinded symbols (Bybit is spot-only).
- `DzengiTickerLatestPriceProvider` (priority 2): single GET `/api/v2/ticker/24hr` with 4 MB codec buffer → maps `XXX/USD`, `XXX.` (equity), `XXX/USDT`. Equity tickers (ending with `.`) are only returned for EQUITY-kinded symbols to prevent false divergences.

Both providers return `NormalizedLatestQuote` (canonical symbol, USD price, quote currency, source, source symbol, pricedAt).

### 3. Equity vs Crypto Symbol Disambiguation

Dzengi equity instruments use a trailing-dot convention in native tickers (e.g. `U.` = Unity Software, `TSLA.` = Tesla). On Bybit, `UUSDT` is an unrelated crypto token. If both sources resolved the same canonical `U`, a spurious 8000× divergence would be logged and the wrong price could win.

Fix: `TrackedAssetRegistryMaintainer` uses EQUITY as the authoritative kind when a Dzengi equity symbol and a Bybit crypto symbol share the same canonical name (EQUITY overrides CRYPTO in the map). `DzengiTickerLatestPriceProvider` only matches dot-suffix tickers against EQUITY-kinded symbols. `BybitTickerLatestPriceProvider` skips EQUITY-kinded symbols entirely.

### 4. Refresh Orchestration

`LatestPriceRefreshService.refresh()`:
1. Loads `tracked_price_assets` (kind + symbol) — stablecoins excluded (pinned by consumers).
2. Calls each provider's `fetchAll()` with the full kind map.
3. Logs divergences (relative difference > `divergenceTolerancePct = 5%`) between providers for the same symbol.
4. Upserts one `current_price_quotes` document per (symbol, source) pair.
5. Never modifies `historical_prices`.

`LatestPriceRefreshJob` drives the cycle:
- Fires once on `ApplicationReadyEvent`.
- Fires again on a `@Scheduled(fixedDelayString)` every `refreshIntervalMs` (default 30 min, after the previous cycle completes).
- `AtomicBoolean` guard prevents overlapping runs.

### 5. Unified Read Service (`CurrentPriceReadService`)

All consumers (`SessionDashboardQueryService`, `SessionLpQueryService`, `SessionLendingQueryService`) now call:

```java
Map<String, ResolvedPrice> prices = currentPriceReadService.resolveLatest(symbols);
```

`CurrentPriceReadService`:
1. Pins stablecoins at $1 locally (no DB query needed).
2. Queries `current_price_quotes` for remaining symbols.
3. Delegates to `LatestPriceSelectionPolicy` which picks the best quote by: freshness → source priority → timestamp recency. Marks quotes stale if `pricedAt` older than `staleAfterMs` (default 90 min).
4. Logs price divergences when multiple fresh quotes disagree by more than the tolerance.

Both `SessionLendingQueryService` and `SessionLpQueryService` apply the same stablecoin-pin locally before calling the service, to ensure consistent behavior even when the service is mocked in tests.

### 6. `CurrentPriceQuoteRefreshService` Shrunk

The old service is reduced to two responsibilities only:
- Pinning stablecoins at $1 in `current_price_quotes` (legacy consumers).
- Refreshing GMX protocol-snapshot-derived prices.

Bybit/Dzengi live symbol refresh is removed from it entirely.

---

## Consequences

### Good
- Single bulk call per source per cycle — no per-symbol HTTP calls.
- Dashboard, LP, and lending pages all read from the same `current_price_quotes` rows via `CurrentPriceReadService`.
- LP TVL re-values at current price instead of entry price (mark-to-market).
- `historical_prices` is never touched — AVCO remains deterministic.
- Dzengi equity positions (TSLA, GOOGL, etc.) now get live prices.
- Provider failures are isolated — one source failing does not stop the other.
- Divergence guard logs suspicious cross-source disagreements.

### Known Limitations
- **USD-stable $1 pin masks depeg.** USDT, USDC, DAI are permanently pegged at $1. A stablecoin de-peg event would not be reflected in the dashboard.
- **EUR-stable handling.** BYN (Dzengi fiat) and EUR are not priced by either source; their prices come from a separate Dzengi kline-based mechanism (ADR-050).
- **LP quantity freshness is bounded by `LpPositionRefreshService` cadence.** TVL accuracy is limited by how fresh the stored LP token quantities are. A price update with stale quantities gives an approximate TVL.
- **Concentrated-liquidity stored-qty-at-new-price is approximate.** For Uniswap v3 / Algebra positions, the token decomposition depends on the current price; stored quantities approximate this.
- **Long-tail assets unresolved by either source.** 19/48 tracked non-stablecoin symbols received no quote in the initial test cycle. These remain unpriced; consumers show stale or missing price, never $0 or entry price.

---

## Configuration (`application.yml`)

```yaml
walletradar:
  pricing:
    latest:
      refresh-interval-ms: 1800000    # 30 minutes
      divergence-tolerance-pct: 0.05  # 5%
      stale-after-ms: 5400000         # 90 minutes
      registry-prune-ttl-days: 7
```

---

## Files

| File | Role |
|---|---|
| `pricing/latest/LatestPriceProvider.java` | SPI |
| `pricing/latest/NormalizedLatestQuote.java` | Provider output VO |
| `pricing/latest/ResolvedPrice.java` | Consumer-facing VO |
| `pricing/latest/LatestPriceRefreshResult.java` | Cycle statistics |
| `pricing/latest/LatestPriceProperties.java` | Config properties |
| `pricing/latest/BybitTickerClient.java` | Bybit HTTP bulk fetcher |
| `pricing/latest/BybitTickerLatestPriceProvider.java` | Bybit provider |
| `pricing/latest/DzengiTickerLatestPriceProvider.java` | Dzengi provider |
| `pricing/latest/LatestPriceSelectionPolicy.java` | Best-quote selector |
| `pricing/latest/CurrentPriceReadService.java` | Shared read service |
| `pricing/latest/TrackedPriceAssetDocument.java` | Registry document |
| `pricing/latest/TrackedPriceAssetRepository.java` | Registry repository |
| `pricing/latest/TrackedAssetRegistryMaintainer.java` | Registry builder |
| `pricing/latest/LatestPriceRefreshService.java` | Cycle executor |
| `pricing/latest/LatestPriceRefreshJob.java` | Scheduled job |
| `pricing/latest/LatestPriceConfiguration.java` | Spring config |
| `cex/acquisition/venue/dzengi/DzengiApiClient.java` | Added `fetchTicker24hr()` |
