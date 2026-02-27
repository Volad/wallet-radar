# Feature 11: Inline swap price when one leg is stablecoin

**Tasks:** T-031

**Doc refs:** ADR-018, docs/01-domain.md (Price Sources), docs/02-architecture.md (Data Flow 1), docs/03-accounting.md (Price Resolution)

---

## T-031 — Inline price from swap legs (stablecoin leg only)

- **Module(s):** ingestion (job), common
- **Description:** At ingestion (Phase 1), for each swap where **exactly one leg is a stablecoin** (per `StablecoinRegistry`), set `priceUsd`, `priceSource`, and `totalValueUsd` from the swap ratio **before** upsert. Do not set `PRICE_PENDING` for those events. DeferredPriceResolutionJob must skip events that already have `priceUsd` (or `priceSource` in STABLECOIN, SWAP_DERIVED) so inline resolution is not overwritten.
- **Rationale:** Domain already prefers SWAP_DERIVED; we have both legs (income/outcome) in the same tx, so we can derive price without external API. Reduces PRICE_PENDING and CoinGecko load (ADR-018).
- **DoD:**
  - New component (e.g. `InlineSwapPriceEnricher`) or equivalent step inside `ClassificationProcessor`: after normalizer, before upsert.
  - Group normalized events by `txHash`; for each tx, find pairs (SWAP_SELL, SWAP_BUY) with different `assetContract`.
  - For each pair: if exactly one asset is in `StablecoinRegistry`, set for stablecoin leg: `priceUsd=1`, `priceSource=STABLECOIN`, `totalValueUsd = |quantityDelta|`; for the other leg: `priceUsd = |stablecoinAmount| / |otherAmount|`, `priceSource=SWAP_DERIVED`, `totalValueUsd = |quantityDelta| * priceUsd`. Do not set PRICE_PENDING on these events.
  - `DeferredPriceResolutionJob`: when resolving PRICE_PENDING, skip (or do not overwrite) events that already have non-null `priceUsd` or `priceSource` in (STABLECOIN, SWAP_DERIVED).
  - Unit tests: enricher/step with mocked StablecoinRegistry; pair with one stablecoin → both events get price and totalValueUsd; pair with no stablecoin or two stablecoins → unchanged (still PRICE_PENDING or stablecoin $1 only). DeferredPriceResolutionJob test: events with priceUsd set are not updated.
- **Dependencies:** T-009 (backfill), existing normalizer and StablecoinRegistry

---

## Implementation steps

### Step 1: Inline swap price component

- **Location:** `com.walletradar.ingestion` (e.g. `job` package or a small `enricher` subpackage). Name: `InlineSwapPriceEnricher` or equivalent.
- **Contract:** Accept a list of `EconomicEvent` (for one segment batch or per-tx group), mutate in place: for swaps with exactly one stablecoin leg, set `priceUsd`, `priceSource`, `totalValueUsd`; do not add or remove events.
- **Logic:**
  - Group events by `(txHash, networkId, walletAddress)` (or by txHash if batch is already scoped).
  - For each group, select events with `eventType` SWAP_SELL or SWAP_BUY; form pairs (one SWAP_SELL, one SWAP_BUY) with different `assetContract`.
  - For each pair, resolve `assetContract` via `StablecoinRegistry.isStablecoin(contract, networkId)` (or equivalent). If exactly one is stablecoin:
    - Stablecoin leg: `priceUsd = 1`, `priceSource = STABLECOIN`, `totalValueUsd = |quantityDelta|`.
    - Other leg: `priceUsd = |stablecoinQuantityDelta| / |otherQuantityDelta|`, `priceSource = SWAP_DERIVED`, `totalValueUsd = |quantityDelta| * priceUsd`.
  - Do not set `PRICE_PENDING` on these events (FlagService is applied after this step; only set PRICE_PENDING for events that still have no price).
- **Dependencies (constructor):** `StablecoinRegistry` (from common). No need for HistoricalPriceResolver in this step.

### Step 2: Integrate into ClassificationProcessor

- After `EconomicEventNormalizer` (or equivalent) produces the list of events for the segment (or per-tx), call the enricher on that list (or on events grouped by txHash), then apply FlagService (set PRICE_PENDING only for events where `priceUsd` is null), then `IdempotentEventStore.upsert()`.
- Ensure segment processor does not overwrite `priceUsd`/`priceSource`/`totalValueUsd` when they are already set (enricher only sets when both legs are present and exactly one is stablecoin).

### Step 3: DeferredPriceResolutionJob — skip already-resolved events

- When loading or processing events with PRICE_PENDING, exclude (or skip update for) events that already have `priceUsd != null` or `priceSource` in (STABLECOIN, SWAP_DERIVED). So Phase 2 does not overwrite inline-resolved prices.
- When updating resolved price, set both `priceUsd` and `totalValueUsd = |quantityDelta| * priceUsd` for consistency (if not already done elsewhere).

### Step 4: Tests

- **InlineSwapPriceEnricherTest (or equivalent):**
  - Two events, same txHash: SWAP_SELL −10 USDC, SWAP_BUY +0.00011 WBTC; USDC in StablecoinRegistry → both get priceUsd and totalValueUsd, stablecoin $1, WBTC = 10/0.00011; no PRICE_PENDING.
  - Both legs non-stable → no change (events keep priceUsd null).
  - Both legs stable (USDC/USDT) → each gets $1 and totalValueUsd (optional: or leave as-is if product treats both as $1 already).
- **DeferredPriceResolutionJob:** Unit or integration test: given events where some already have priceUsd or SWAP_DERIVED, job does not overwrite them.

---

## Acceptance criteria

- Swaps with exactly one stablecoin leg get `priceUsd`, `priceSource`, and `totalValueUsd` set at ingestion; those events do not have PRICE_PENDING.
- DeferredPriceResolutionJob does not overwrite events that already have priceUsd or priceSource STABLECOIN/SWAP_DERIVED.
- `./gradlew :backend:test` passes.
- Behaviour is consistent with ADR-018 and docs (01-domain, 02-architecture, 03-accounting).
