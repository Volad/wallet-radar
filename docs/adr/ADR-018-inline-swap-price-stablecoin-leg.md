# ADR-018: Inline price from swap when one leg is stablecoin

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** System Architect, Business Analyst

---

## Context

- A single on-chain swap is stored as **two economic events** (SWAP_SELL and SWAP_BUY) with the same `txHash`, different `assetContract` and `quantityDelta` (e.g. outcome 10 USDC, income 0.00011 WBTC).
- When **one leg is a stablecoin** (USDC, USDT, DAI, GHO, USDe, FRAX), the USD price of the other leg is uniquely determined: `priceUsd(volatile) = |stablecoinAmount| / |volatileAmount|`. The stablecoin leg has `priceUsd = 1`, source STABLECOIN.
- Currently, **all** non-stablecoin events are marked `PRICE_PENDING` at ingestion (Phase 1), and Phase 2 (`DeferredPriceResolutionJob`) resolves prices via `HistoricalPriceResolver` (CoinGecko, etc.), without using the swap pair. So we do not set price from the swap ratio at ingestion even when it is available.
- Domain and accounting already treat **SWAP_DERIVED** as a preferred source (01-domain Price Sources, 03-accounting chain). Using execution price from the swap is correct for cost basis and display.

---

## Decision

1. **Inline resolution at ingestion:** For swaps where **exactly one leg is a stablecoin** (according to `StablecoinRegistry`), set `priceUsd`, `priceSource` (STABLECOIN for the stablecoin leg, SWAP_DERIVED for the other), and `totalValueUsd = |quantityDelta| × priceUsd` **during segment processing**, after normalizing events and **before** upsert. Do **not** set `PRICE_PENDING` for these events.
2. **Placement:** The logic runs after `EconomicEventNormalizer` (so we have both SWAP_SELL and SWAP_BUY for the same tx), before `IdempotentEventStore.upsert()`. It can live in a dedicated component (e.g. `InlineSwapPriceEnricher`) invoked from `ClassificationProcessor`, or as a step inside the processor.
3. **DeferredPriceResolutionJob:** Resolve only events that still have `PRICE_PENDING`. Do **not** overwrite events that already have `priceUsd` set (or that have `priceSource` in STABLECOIN, SWAP_DERIVED). This keeps inline resolution as the single source of truth for stablecoin-leg swaps.
4. **Scope:** Apply only when both legs of the swap are present in the same batch (same tx). Multi-hop swaps (e.g. USDC→ETH→WBTC in one tx) and pairs with **no** stablecoin leg remain `PRICE_PENDING` and are resolved in Phase 2.

---

## Consequences

### Positive

- **Fewer PRICE_PENDING events** → fewer CoinGecko calls and faster “ready” portfolio after backfill.
- **Single source of truth** for stablecoin-leg swaps: price fixed at ingestion from on-chain data, no dependency on external API for these events.
- **Alignment with domain:** 01-domain and 03-accounting already prioritise SWAP_DERIVED; we apply it at ingestion where possible.
- **Consistency:** AVCO and display use the same execution price; no “first no price, then overwrite” for these swaps.

### Trade-offs

- **New component/step:** Inline swap price logic must group events by `txHash`, find (SWAP_SELL, SWAP_BUY) pairs, check stablecoin via `StablecoinRegistry`, and compute price and totalValueUsd. Idempotent and deterministic so re-runs do not change stored values.
- **Two paths:** Stablecoin-leg swaps get price inline; all other events still go through DeferredPriceResolutionJob. This is intentional and documented.

### Risks

- **Depeg:** If a “stablecoin” is not $1 at execution time, we still use the swap ratio (execution price). For analytics this is usually desirable (true execution); if product later needs “mark” price, policy can be extended.

---

## References

- **docs/01-domain.md** — Price Sources (STABLECOIN, SWAP_DERIVED).
- **docs/03-accounting.md** — Historical price resolution chain.
- **docs/02-architecture.md** — Data Flow 1 (Initial Wallet Backfill), RawFetchSegmentProcessor, ClassificationProcessor.
- **docs/tasks/11-inline-swap-price.md** — Implementation task T-031.
