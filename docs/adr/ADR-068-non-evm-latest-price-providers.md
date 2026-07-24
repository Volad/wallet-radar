# ADR-068: Non-EVM latest-price providers — Jupiter for Solana SPL tokens

**Status:** Accepted  
**Date:** 2026-07-19  
**Scope:** Latest-price refresh subsystem (`current_price_quotes`), Solana SPL symbol resolution, on-chain balance display

---

## Context

After non-EVM on-chain balances landed (ADR-067), Solana SPL holdings surfaced on the dashboard but were **unpriced** and displayed with the **raw base58 mint as the symbol**. In `on_chain_balances` (networkId=SOLANA) many rows had `assetSymbol == assetContract` (e.g. `mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So` for mSOL, `bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1` for bSOL, several `…pump` memecoins), and the latest-price refresh reported a large `pricedByNeither` (unresolved) count.

The existing latest-price providers — `BybitTickerLatestPriceProvider`, `DzengiTickerLatestPriceProvider` — are CEX ticker feeds keyed by market symbol. They price majors (including native SOL/TON) but cannot price SPL memecoins, which trade only on Solana DEXes and are identified by **mint**, not ticker. Two gaps had to be closed:

1. **Symbol resolution** — turn an unknown SPL mint into a real ticker so the asset is tracked and displayed correctly.
2. **Pricing** — obtain a USD price for SPL mints that no CEX lists.

Constraint: **free public APIs only**, no paid indexers, respect rate limits.

---

## Decision

Add **Jupiter** (the canonical Solana price/token aggregator, free tier) as a non-EVM data source. Jupiter is the standard Solana price source (powers jup.ag, Phantom, Solflare).

### Endpoints settled on

| Purpose | Endpoint | Response field |
|---|---|---|
| SPL USD price by mint | `GET /price/v3?ids={comma-separated-mints}` | `{ "<mint>": { "usdPrice": <number>, "decimals": … } }` |
| SPL mint → symbol/decimals | `GET /tokens/v2/search?query={mint}` | JSON array; the element whose `id == mint` provides `{ "symbol", "decimals", "name" }` |

- **Host selection:** when `JUPITER_API_KEY` is set, the keyed host `https://api.jup.ag` is used with an `x-api-key` header; otherwise the no-key host `https://lite-api.jup.ag` is used. Explicit URL overrides (`price-url`, `token-url`) win over both.
- **Price v3** caps at **50 mint ids per request** — the provider batches at `max-ids-per-request` (default 50).
- **Defensive parsing:** the client reads `usdPrice` (v3) and falls back to `data.<mint>.price` (v2 legacy) so a Jupiter response-shape change does not silently zero prices. Symbol/decimals come from the Tokens **v2** `search` endpoint (the v1 `token/{mint}` endpoint returns 404 / was retired); `fetchTokenMetadata` parses the returned array and selects the element whose `id` equals the mint (single-object responses are still accepted defensively).

### Components

- **`JupiterClient` / `WebClientJupiterClient` (platform, `networks.solana.jupiter`)** — WebClient-backed, mirroring the Helius client resilience pattern: bounded in-memory buffer, per-request timeout, exponential backoff on transient failures (HTTP 429/5xx, I/O, timeout), and a shared client-side `JupiterRequestThrottle` (min interval between requests). **Never throws** — any venue error resolves to an empty result so the refresh cycle proceeds. Bound via `JupiterProperties` (`walletradar.pricing.jupiter.*`) and `JupiterConfig`.
- **`JupiterSplTokenMetadataResolver` (core, `normalization.pipeline.solana`)** — resolves mint → {symbol, decimals} in a fixed order: **(1)** config-seeded `SolanaSplTokenMetadataRegistry` (USDC/USDT/wSOL, exact base58 match, no network call); **(2)** Jupiter Tokens API, cached in a Caffeine cache (10k entries / 24h TTL, negative results cached to avoid re-querying dead mints); **(3)** empty → caller keeps the raw mint. Wired into `SolanaOnChainBalanceProvider` so `assetSymbol` becomes the real ticker (mint stays in `assetContract`); base58 case-sensitivity preserved; small timeout + graceful mint fallback (never blocks the snapshot).
- **`JupiterPriceLatestPriceProvider` (core, `pricing.latest`)** — a `LatestPriceProvider` with `source() = PriceSource.JUPITER` and a **lower priority than Bybit/Dzengi** (so majors still come from the CEX venues; Jupiter is the only source for SPL memecoins). It derives `canonicalSymbol → mint` from `on_chain_balances` (networkId=SOLANA, positive quantity), **skipping** native `NATIVE:SOLANA` and pinned USD stablecoins (`CanonicalAssetCatalog.isUsdStablecoin`), restricted to the cycle's wanted symbol set. Prices are batch-fetched and upserted to `current_price_quotes` keyed by the resolved canonical symbol with `sourceSymbol = mint`. When several mints canonicalize to one symbol, the mint held in the largest on-chain quantity wins (collision logged at debug).

### Storage keying

SPL assets are **priced by mint** but **stored by resolved symbol** in `current_price_quotes` (`symbol:source` id via `CurrentPriceQuoteDocument.composeId`), consistent with every other latest-price provider. The mint is retained as `sourceReference` for traceability.

### tracked_price_assets

No change to `TrackedAssetRegistryMaintainer`: it already unions `on_chain_balances` symbols into the registry, so once symbols resolve they become tracked `CRYPTO`. If a symbol is still an unresolved mint, it stays tracked and the price provider still maps symbol→mint from `on_chain_balances`, so Jupiter can price it regardless.

---

## Consequences

- Solana SPL holdings (mSOL, bSOL, `…pump` memecoins) now display real tickers and carry a USD price; `pricedByNeither` drops accordingly.
- **Zero EVM impact.** Bybit/Dzengi providers, EVM pricing, and the selection policy are untouched. Stablecoins remain pinned (never priced via Jupiter). Native SOL/TON continue to be priced by the CEX venues.
- Free-tier only: throttle + retry/backoff + cache keep the burst under Jupiter's limits; the no-key `lite-api` host works when `JUPITER_API_KEY` is absent.
- Failure is graceful: an unreachable Jupiter yields empty results — SPL falls back to mint-as-symbol and stays unpriced, never failing the refresh cycle or the portfolio snapshot.

### Follow-ups / notes

- Jupiter Tokens v2 `search` is the active metadata endpoint; the retired v1 `token/{mint}` path is no longer used.
- Price v3 covers tokens traded in the last 7 days; long-dormant memecoins may remain unpriced (acceptable — they are dust).

---

## References

- ADR-063 / ADR-067 — Solana Helius normalization; non-EVM on-chain balances (metadata registries, balance providers).
- `LatestPriceProvider` SPI, `LatestPriceRefreshService` — bulk latest-price subsystem writing only to `current_price_quotes`.
- `HeliusRequestThrottle` / `WebClientHeliusSolanaClient` — resilience pattern mirrored by the Jupiter client.

---

## Amendment (2026-07-20) — WS-6: swap-derived-first, TON jetton feed, xStock mapping, registry seeding

**Context:** Solana/TON move-basis pricing (B4). After WS-1/WS-2 normalized both legs of Solana and
TON swaps, the **primary** basis mechanism is the event-local `SwapDerivedPriceResolver`
(`@Order(20)`), not external feeds. Swap-derived yields only a **ratio**, so it needs an external
price for **one** leg (a stable/native/externally-priced anchor). This is a general principle for all
long-tail assets without a stable leg — external latest-price providers are a **fallback** for the
anchor/result leg and for current mark-to-market quotes only.

**Decisions added:**

1. **wSOL canonicalizes to SOL.** `CanonicalAssetCatalog` aliases `WSOL→SOL`. A wSOL swap leg is no
   longer a distinct asset: it neither trips the swap-derived circular guard against a native-SOL leg
   nor is priced as a separate SPL. `JupiterPriceLatestPriceProvider` explicitly **skips the wrapped
   SOL mint** (`So111…112`) so native SOL is always CEX-priced, never Jupiter-priced.

2. **TON jetton price provider (`STON_FI`).** New `TonJettonLatestPriceProvider` (core) +
   `TonPriceClient`/`WebClientTonPriceClient` (platform, `networks.ton.price`) backed by the free
   public STON.fi `GET /v1/assets` feed. It mirrors the Jupiter provider: maps `on_chain_balances`
   (networkId=TON) `assetSymbol→canonical`, `assetContract→jetton master`, skips native TON and
   USD-pinned stablecoins, and prices held jettons by master (STON, XAUt, other DEX-listed jettons).
   Address forms are matched on the canonical raw `workchain:hex` key so STON.fi `EQ…` and on-chain
   `0:hex` forms reconcile. Never throws; throttled + retry/backoff; `walletradar.pricing.ton.*`.

3. **xStock jettons → equity quotes.** `AMZNx→AMZN`, `MSTRx→MSTR` canonical aliases (identity only,
   no hardcoded price). `CanonicalAssetCatalog.isEquityBacked(...)` marks these so
   `TrackedAssetRegistryMaintainer` stamps `EQUITY` kind and the Dzengi equity ticker (`SYMBOL.`)
   matches. `XAUt0→XAUT` with a CoinGecko (`tether-gold`) historical fallback; current marks from the
   TON jetton feed.

4. **Registry seeding ordering fix.** `TrackedAssetRegistryMaintainer` now **seeds every
   wallet-supported network's native symbol first** (descriptor-driven via `NetworkRegistry`), so
   non-EVM majors (SOL/TON) are tracked even on the first cycle where the registry is rebuilt before
   non-EVM `on_chain_balances` populate.

5. **Explicit UNPRICED (no change, confirmed).** When both legs are illiquid with no anchor,
   swap-derived returns empty, the external chain misses, and `PriceResolutionService.markUnknown`
   leaves `unitPriceUsd=null`/`valueUsd=null` with `PriceSource.UNKNOWN` and stamps
   `PRICE_UNRESOLVABLE` — an explicit unpriced state, never a $0 covered basis.
