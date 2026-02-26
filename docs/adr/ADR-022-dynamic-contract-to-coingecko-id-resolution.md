# ADR-022: Dynamic Contract-to-CoinGecko-ID Resolution

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** System Architect

---

## Context

WalletRadar's pricing module resolves historical USD prices via CoinGecko `/coins/{id}/history`. Currently, the **coin ID** is obtained from a static config map (`walletradar.pricing.contract-to-coin-gecko-id`). This requires manual maintenance for every new token and does not scale as users add wallets with diverse assets across multiple networks.

CoinGecko provides two APIs for dynamic contract→coin-ID resolution:

1. **`/coins/list?include_platform=true`** — Returns all coins with `platforms: { platformId: contractAddress }`. One bulk call (~15k+ entries), no per-token rate limit.
2. **`/onchain/networks/{network}/tokens/{address}/info`** — Returns `coingecko_coin_id` per token on a specific network. Per-token call, shares rate limit with other CoinGecko endpoints.

**Goals:**
- Replace static config as the primary source with dynamic lookup.
- Keep config as **optional override** for manual corrections (e.g. wrong mapping, bridged tokens).
- Minimize API calls and respect rate limits (45 req/min for historical).
- Integrate cleanly with `CoinGeckoHistoricalResolver` and `HistoricalPriceRequest`.

---

## Decision

### 1. ContractToCoinGeckoIdResolver Interface

```java
package com.walletradar.pricing.resolver;

import com.walletradar.domain.NetworkId;
import java.util.Optional;

/**
 * Resolves token contract address + network to CoinGecko coin ID.
 * Used by CoinGeckoHistoricalResolver and SpotPriceResolver.
 */
public interface ContractToCoinGeckoIdResolver {

    /**
     * @param contractAddress token contract (EVM: 0x..., Solana: base58). Normalized lowercase.
     * @param networkId blockchain network
     * @return CoinGecko coin id (e.g. "ethereum", "weth") or empty if unknown
     */
    Optional<String> resolve(String contractAddress, NetworkId networkId);
}
```

**Resolution order (chain-of-responsibility):**
1. **Config override** — if `contract-to-coin-gecko-id` contains entry for contract (network-agnostic in config for backward compatibility), use it.
2. **Dynamic lookup** — CoinsListBulkResolver (cached) or OnchainTokenInfoResolver (per-token fallback).

---

### 2. NetworkId → CoinGecko Platform ID Mapping

CoinGecko uses **asset platform IDs** in `coins/list` and **onchain network IDs** in `/onchain/networks/...`. These differ.

| NetworkId | Asset Platform ID (coins/list) | Onchain Network ID (GeckoTerminal) |
|-----------|-------------------------------|------------------------------------|
| ETHEREUM  | ethereum                      | eth                                |
| ARBITRUM  | arbitrum-one                  | arbitrum                           |
| OPTIMISM  | optimism                      | optimism                           |
| POLYGON   | polygon-pos                   | polygon_pos                        |
| BASE      | base                          | base                               |
| BSC       | binance-smart-chain           | bsc                                |
| AVALANCHE | avalanche                     | avalanche                          |
| MANTLE    | mantle                        | mantle                             |
| SOLANA    | solana                        | solana                             |

**Implementation:** Static `NetworkIdToCoinGeckoPlatformMapper` utility. Onchain IDs verified at implementation time via `/onchain/networks` (free endpoint, call once at startup or document in config).

---

### 3. Implementations

#### 3.1 ConfigOverrideContractResolver (primary for overrides)

- Wraps `PricingProperties.getContractToCoinGeckoId()`.
- Key: `contractAddress.toLowerCase().strip()` (config remains network-agnostic for backward compatibility).
- Returns `Optional.of(coinId)` if present, else `Optional.empty()`.
- **First** in the chain — manual overrides always win.

#### 3.2 CoinsListBulkResolver (primary dynamic source)

- Fetches `GET /coins/list?include_platform=true` once (or on cache miss).
- Builds reverse index: `(platformId, contractAddress) → coinId`.
- For each coin in response: iterate `platforms`; for each `(platformId, addr)` insert `key(platformId, addr) → coin.id`.
- **Cache:** Caffeine cache `contractToCoinIdCache` with key `platformId + ":" + contractAddress.toLowerCase()`, TTL 24h, max 15_000.
- **Bulk list cache:** Separate cache for the raw list (or in-memory field), TTL 24h. Refresh on first miss or background refresh.
- **Rate limit:** `coins/list` is one call per 24h — does **not** consume the 45/min historical budget. Use a dedicated one-off call at startup or lazy load.
- **Fallback:** If `coins/list` fails (network, 429), log and return empty — per-token resolver may still succeed.

#### 3.3 OnchainTokenInfoResolver (fallback for tokens not in coins/list)

- Calls `GET /onchain/networks/{onchainNetworkId}/tokens/{address}/info`.
- Parses `coingecko_coin_id` from response.
- **Cache:** Same `contractToCoinIdCache` key format. TTL 24h.
- **Rate limit:** Shares 45 req/min with historical price calls. Use `RateLimiter.acquire()` before each call. Consider a **separate** rate limiter for ID resolution if needed (e.g. 10/min for ID, 35/min for history) — document as config option.
- **When to use:** Only when CoinsListBulkResolver returns empty. Many long-tail tokens appear in GeckoTerminal but not in `coins/list`.

---

### 4. Composite Resolver (Chain)

```
ConfigOverrideResolver
    → if hit: return
    → else: CoinsListBulkResolver
        → if hit: return
        → else: OnchainTokenInfoResolver
            → return (or empty)
```

`ChainedContractToCoinGeckoIdResolver` composes the three, invokes in order.

---

### 5. Cache Strategy

| Cache Name              | Key Format                          | TTL   | Max Size | Notes                                      |
|-------------------------|-------------------------------------|-------|----------|--------------------------------------------|
| `contractToCoinIdCache` | `{platformId}:{contractAddress}`    | 24 h  | 15 000   | Negative cache: store "unknown" with shorter TTL (1h) to avoid repeated failed lookups |
| `coinsListBulk`         | `"coins-list"` (singleton)          | 24 h  | 1        | Raw list or derived map; refresh on miss   |

**Key format rationale:** `platformId` ensures USDC on Ethereum (0xa0b8...) vs USDC on Polygon (0x2791...) resolve to correct coin IDs when the same contract exists on multiple chains (it doesn't — different addresses; but platformId keeps the index correct).

**Negative caching:** If OnchainTokenInfoResolver returns 404 or empty, cache `Optional.empty()` with TTL 1h to avoid hammering the API for unknown tokens.

---

### 6. Integration with CoinGeckoHistoricalResolver

**Before:**
```java
String coinId = pricingProperties.getContractToCoinGeckoId().get(request.getAssetContract().toLowerCase());
```

**After:**
```java
Optional<String> coinIdOpt = contractToCoinGeckoIdResolver.resolve(
    request.getAssetContract().toLowerCase().strip(),
    request.getNetworkId());
if (coinIdOpt.isEmpty()) {
    return PriceResolutionResult.unknown();
}
String coinId = coinIdOpt.get();
```

**HistoricalPriceRequest** already has `assetContract` and `networkId` — no change. **DeferredPriceResolutionJob** and **ClassificationProcessor** already pass `networkId` when building the request.

**SpotPriceResolver** must also use the resolver. Spot currently uses only `contractAddress`; it should accept `networkId` (e.g. from `AssetPosition` or caller context). If spot is used without network (e.g. legacy), fallback: try all platforms for that contract or use first non-empty. Document as implementation detail.

---

### 7. Config as Optional Override

- `contract-to-coin-gecko-id` in `application.yml` **remains**.
- Entries in config **override** dynamic lookup.
- Use cases: (1) CoinGecko maps a bridged token to wrong coin — override to correct id. (2) New token not yet in CoinGecko — not applicable (override can't fix missing data). (3) Prefer specific coin when multiple exist — override.
- Config keys stay **contract-only** (no network in key) for backward compatibility. If the same contract exists on multiple networks with different coin IDs, config override applies to all — document this limitation.

---

### 8. Rate Limiting

| Endpoint                         | Calls                    | Rate Limit              |
|----------------------------------|--------------------------|-------------------------|
| `/coins/list?include_platform=true` | 1 per 24h (or startup)   | Not part of 45/min      |
| `/onchain/.../tokens/{addr}/info`   | Per unique (contract, network) | Share 45/min with historical |
| `/coins/{id}/history`            | Per (coinId, date)       | 45/min (existing)       |

**Strategy:**
- `coins/list`: Call at application startup (or on first resolver miss). Cache 24h. Does not use `RateLimiter`.
- `onchain/.../info`: Use same `RateLimiter` as historical, or a **dedicated** limiter (e.g. 5/min for ID resolution, 40/min for history) to avoid ID lookups starving price calls. **Recommendation:** Single limiter 45/min — ID resolution is infrequent (cache hits after warm-up); only first-time tokens trigger onchain call.
- Historical: Unchanged — `rateLimiter.acquire()` before `/coins/{id}/history`.

---

### 9. Data Flow

```
EconomicEvent (assetContract, networkId)
    → DeferredPriceResolutionJob.buildCacheKey(assetContract, date)
    → HistoricalPriceRequest(assetContract, networkId, blockTimestamp)
    → HistoricalPriceResolverChain
        → StablecoinResolver (skip if not stablecoin)
        → SwapDerivedResolver (skip if no swap leg)
        → CoinGeckoHistoricalResolver
            → contractToCoinGeckoIdResolver.resolve(assetContract, networkId)
                → ConfigOverride → CoinsListBulk → OnchainTokenInfo
            → coinId
            → GET /coins/{coinId}/history?date=YYYY-MM-DD
            → PriceResolutionResult
```

---

## Module Boundaries

| Component                         | Package                    | Dependencies                          |
|-----------------------------------|----------------------------|---------------------------------------|
| `ContractToCoinGeckoIdResolver`   | `pricing.resolver`         | `domain.NetworkId`                    |
| `ConfigOverrideContractResolver`  | `pricing.resolver`         | `PricingProperties`                    |
| `CoinsListBulkResolver`           | `pricing.resolver`         | `WebClient`, `Caffeine`, `PricingProperties` |
| `OnchainTokenInfoResolver`        | `pricing.resolver`         | `WebClient`, `Caffeine`, `RateLimiter`, platform mapper |
| `ChainedContractToCoinGeckoIdResolver` | `pricing.resolver`   | All three resolvers                    |
| `NetworkIdToCoinGeckoPlatformMapper` | `pricing.config` or `pricing.resolver` | `NetworkId`                    |
| `CoinGeckoHistoricalResolver`     | `pricing.resolver`         | `ContractToCoinGeckoIdResolver` (injected) |
| `SpotPriceResolver`                | `pricing`                  | `ContractToCoinGeckoIdResolver` (needs networkId from caller) |

---

## Implementation Notes for Worker

1. **Add `contractToCoinIdCache` to CaffeineConfig** — 24h TTL, 15k max. Optional: `contractToCoinIdNegativeCache` for unknown tokens, 1h TTL, 5k max.
2. **CoinsListBulkResolver:** Parse JSON array from `/coins/list?include_platform=true`. Each element: `{ "id": "ethereum", "symbol": "eth", "name": "Ethereum", "platforms": { "ethereum": "0x...", "arbitrum-one": "0x..." } }`. Build `Map<String, String>` keyed by `platformId + ":" + addr.toLowerCase()`. Handle `platforms` being null or empty. Use `@PostConstruct` or lazy init to fetch on first `resolve()`.
3. **OnchainTokenInfoResolver:** Verify response schema for `coingecko_coin_id` (field name may vary). Handle 404 → cache negative. Use `RateLimiter` from `PricingConfig`.
4. **SpotPriceResolver:** Callers (e.g. SnapshotBuilder) must pass `networkId` when building spot requests. If no network available, try resolver with `null` networkId → ConfigOverride only, or document "best-effort" behavior.
5. **Tests:** Unit test each resolver with mocked WebClient. Integration test with real CoinGecko (optional, rate-limited). Test chain order: config overrides dynamic.
6. **Migration:** No breaking change. Existing config entries continue to work. New tokens resolve dynamically without config.

---

## Consequences

### Positive
- No manual config for most tokens; new tokens work automatically when listed on CoinGecko.
- Config override preserves control for edge cases.
- Single bulk call (`coins/list`) minimizes API usage; per-token fallback for long-tail assets.
- Clear separation: resolver interface, multiple strategies, cache layer.

### Trade-offs
- `coins/list` is large (~2–5 MB); parse and index at startup adds ~1–2s. Acceptable.
- Onchain API may have different coverage than coins/list; fallback chain handles both.
- SpotPriceResolver needs networkId — may require caller changes.

### Risks
- CoinGecko API changes (field names, endpoint paths) — document and version.
- Rate limit exhaustion if many unique tokens in short window — cache mitigates; monitor in production.

---

## References

- **docs/02-architecture.md** — Pricing module, HistoricalPriceResolverChain.
- **docs/03-accounting.md** — Price resolution chain (Stablecoin → Swap → CoinGecko).
- **ADR-005** — Caffeine cache strategy.
- **ADR-016** — Deferred price resolution (T-OPT-5).
- **ADR-018** — Inline swap price (stablecoin leg).
- CoinGecko: `/coins/list`, `/onchain/networks/{network}/tokens/{address}/info`.
