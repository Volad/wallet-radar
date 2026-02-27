# T-012: Dynamic Contract-to-CoinGecko-ID Mapping

> **Status:** Spec  
> **Created:** 2025-02  
> **Scope:** Replace static `contract-to-coin-gecko-id` YAML map with dynamic lookup from CoinGecko API.

---

## 1. Goal (One Sentence)

Replace the static `walletradar.pricing.contract-to-coin-gecko-id` map in `application.yml` with a dynamic lookup that fetches and caches contract→coinId mappings from CoinGecko API, with config overrides and fallback behaviour for unknown tokens.

---

## 2. Context

**Current state:**
- `PricingProperties.contractToCoinGeckoId` is a static `Map<String, String>` (contract → coinId)
- Used by `CoinGeckoHistoricalResolver` and `SpotPriceResolver` for `/coins/{id}/history` and `/simple/price`
- Lookup is **contract-only** (no network); `HistoricalPriceRequest` has `networkId` but resolvers ignore it
- Same contract on different chains (e.g. WETH `0xc02aaa...`) maps to one coinId; native tokens have different wrapped addresses per chain (already distinct by contract)

**Constraint:** CoinGecko Free tier — ~30–50 req/min (docs vary); WalletRadar throttles to 45 req/min for historical. `coins/list` is a single bulk call; onchain API is per-token.

**Approach (Hybrid):**
1. **Config override** — YAML entries take precedence (for corrections, new tokens before CoinGecko lists them)
2. **coins/list cache** — `GET /coins/list?include_platform=true`; build `(platformId, contract) → coinId`; TTL 24h
3. **Onchain API fallback** — `GET /onchain/networks/{network}/tokens/{address}/info` (or equivalent) when cache miss; returns `coingecko_coin_id`; per-token, rate-limited

---

## 3. Acceptance Criteria (Definition of Done)

- [ ] **AC-1** Config override: Any entry in `walletradar.pricing.contract-to-coin-gecko-id` (or new `contract-overrides`) is used first; lookup succeeds without API call.
- [ ] **AC-2** coins/list cache: On startup (or first lookup after expiry), `GET /coins/list?include_platform=true` is called; response is parsed to build `(platformId, contract) → coinId`; cache TTL = 24h (configurable).
- [ ] **AC-3** Network-aware lookup: Lookup key is `(networkId, contractAddress)`; `NetworkId` is mapped to CoinGecko asset platform ID (e.g. ETHEREUM→ethereum, ARBITRUM→arbitrum-one).
- [ ] **AC-4** Onchain fallback: When cache miss and no config override, call CoinGecko onchain API (if available on free tier) for `(network, contract)`; on success, cache result and return coinId.
- [ ] **AC-5** Backward compatibility: Existing `contract-to-coin-gecko-id` entries (contract-only) continue to work; they apply as network-agnostic override (any network).
- [ ] **AC-6** Rate limit: coins/list and onchain fallback calls respect the same 45 req/min token bucket (or a dedicated bucket for mapping fetches).
- [ ] **AC-7** Graceful degradation: If coins/list fails (timeout, 429, 5xx), use config overrides only; log warning; retry on next TTL expiry.
- [ ] **AC-8** Resolver integration: `CoinGeckoHistoricalResolver` and `SpotPriceResolver` obtain coinId from the new mapping service (not `PricingProperties` directly).
- [ ] **AC-9** SpotPriceResolver: Currently takes `contractAddress` only; must receive `networkId` from caller (SnapshotBuilder) for network-aware lookup.
- [ ] **AC-10** Unit and integration tests: Mapping service, cache behaviour, override precedence, fallback when API fails.

---

## 4. Supported vs Unsupported Cases

| Case | Supported | Notes |
|------|-----------|-------|
| Token listed on CoinGecko with platform+contract in coins/list | ✅ | Primary path via cache |
| Token in config override (contract or network+contract) | ✅ | Highest priority |
| Token on supported network (Ethereum, Arbitrum, etc.) | ✅ | Via NetworkId→platformId mapping |
| Token not in CoinGecko (scam, very new, obscure) | ❌ | Returns empty; event flagged PRICE_UNKNOWN |
| CoinGecko API down / 429 / timeout | ⚠️ | Use config overrides only; log; no crash |
| Network not in CoinGecko platform list (e.g. Mantle) | ⚠️ | Fallback to contract-only lookup if platform unknown |
| Onchain API not on free tier | ⚠️ | Fallback disabled; rely on coins/list + overrides |
| Same contract on multiple chains (e.g. WETH) | ✅ | Config override can specify contract-only; cache uses (platform, contract) — same address may appear on multiple platforms |
| Solana tokens (Base58 addresses) | ✅ | Platform `solana`; coins/list includes Solana |

---

## 5. Edge Cases

| Edge Case | Description | In Scope |
|-----------|-------------|----------|
| **Coins/list returns 10k+ coins** | Large payload; parse and index may take 1–2s | ✅ Yes — acceptable at startup/refresh |
| **Platform ID mismatch** | WalletRadar `NetworkId` vs CoinGecko platform ID (e.g. `arbitrum-one` vs `arbitrum`) | ✅ Yes — explicit mapping required |
| **Empty platforms** | Some coins have `platforms: {}` | ✅ Yes — skip; no mapping for that coin |
| **Contract address case** | CoinGecko may return mixed case; normalise to lowercase | ✅ Yes |
| **Duplicate contracts** | Same (platform, contract) for multiple coins (rare) | ⚠️ First wins or config override to disambiguate |
| **TTL expiry during active backfill** | Cache refresh may contend with historical price calls for rate limit | ✅ Yes — use same bucket; refresh during low activity if possible |
| **Mantle / newer chains** | CoinGecko may not have `mantle` in asset_platforms | ⚠️ Out of scope for MVP if not listed; use contract-only override |
| **2-year-old token** | Token created after coins/list cache; not in cache | ✅ Yes — onchain fallback (if available) or config override |

---

## 6. Test Scenarios

### Happy path
- **T1** Config override present → lookup returns override; no API call.
- **T2** Token in coins/list cache → lookup returns coinId; no onchain call.
- **T3** Cache miss, onchain API returns coinId → lookup succeeds; result cached.
- **T4** HistoricalPriceResolver receives (contract, networkId) → mapping service returns coinId → price resolved.

### Edge
- **T5** coins/list returns 429 → use overrides only; no exception.
- **T6** coins/list timeout → use overrides only; log warning.
- **T7** Token not in CoinGecko → mapping returns empty → resolver returns UNKNOWN; event flagged.
- **T8** Same contract on Ethereum and Arbitrum (e.g. WETH) → both resolve to same coinId (weth).
- **T9** USDC on Ethereum vs Arbitrum (different contracts) → each resolves to usd-coin.

### Negative
- **T10** Null/blank contract → mapping returns empty.
- **T11** Unknown NetworkId → fallback to contract-only lookup or empty.
- **T12** Cache TTL expired, coins/list fails → overrides still work.

---

## 7. Task Breakdown (Implementation Order)

| # | Task | Description | Depends on |
|---|------|-------------|------------|
| 1 | **NetworkId → platform ID mapping** | Define `NetworkId` → CoinGecko asset platform ID (ethereum, arbitrum-one, optimistic-ethereum, polygon-pos, base, binance-smart-chain, avalanche, solana). Add Mantle if CoinGecko supports it. | — |
| 2 | **ContractToCoinGeckoMappingService interface** | Interface: `Optional<String> resolve(NetworkId networkId, String contractAddress)`. Implementations: config-first, then cache, then fallback. | 1 |
| 3 | **Config override provider** | Read `contract-to-coin-gecko-id`; support contract-only (any network) and optional `networkId:contract` format for overrides. | — |
| 4 | **CoinsListCacheLoader** | Fetch `GET /coins/list?include_platform=true`; parse; build `(platformId, contractLowercase) → coinId`; store in Caffeine with TTL 24h. | 1 |
| 5 | **Integrate mapping into resolvers** | Replace `pricingProperties.getContractToCoinGeckoId().get(contract)` with `mappingService.resolve(networkId, contract)` in CoinGeckoHistoricalResolver and SpotPriceResolver. Pass `networkId` from callers (DeferredPriceResolutionJob, SnapshotBuilder). | 2, 3, 4 |
| 6 | **Onchain API fallback (optional)** | If coins/list miss and free tier supports it: call onchain token info; parse `coingecko_coin_id`; cache result. Skip if API is Pro-only. | 2, 4 |
| 7 | **Rate limit and error handling** | Ensure coins/list and onchain calls use rate limiter; handle 429/5xx/timeout; log; no crash. | 4, 6 |
| 8 | **Unit tests** | Mapping service (override, cache hit, cache miss, API failure). Resolver integration (mocked mapping). | 2–7 |
| 9 | **Integration test** | With WireMock for CoinGecko: coins/list response; verify cache built; verify lookup. | 4, 8 |

---

## 8. Risk Notes

| Risk | Mitigation |
|------|------------|
| **Onchain API is Pro-only** | Verify free tier; if Pro-only, skip fallback (Task 6); coins/list + overrides sufficient for MVP. |
| **CoinGecko platform IDs change** | Document mapping in config; allow override via `walletradar.pricing.network-to-platform-id` if needed. |
| **Mantle not in CoinGecko** | Use contract-only override for Mantle tokens; or skip dynamic mapping for Mantle. |
| **coins/list rate limit** | Single call per 24h; negligible. Ensure it does not share burst with historical 45/min during refresh. |
| **Cache memory** | 10k coins × ~50 platforms avg × (platformId + contract + coinId) ≈ few MB; acceptable. |

---

## 9. Configuration (Proposed)

```yaml
walletradar:
  pricing:
    # Existing
    coingecko-base-url: https://api.coingecko.com/api/v3
    coingecko-historical-requests-per-minute: 45
    # Overrides (unchanged format; network-agnostic)
    contract-to-coin-gecko-id:
      "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2": "weth"
      # ...
    # New: dynamic mapping
    contract-mapping:
      enabled: true
      coins-list-cache-ttl-hours: 24
      onchain-fallback-enabled: false   # set true if free tier supports
```

---

## 10. References

- `docs/00-context.md` — CoinGecko Free tier, 45 req/min
- `docs/01-domain.md` — PriceSource, PRICE_UNKNOWN
- `docs/03-accounting.md` — Price resolution chain
- CoinGecko: `/coins/list?include_platform=true`, `/asset_platforms`, `/onchain/networks/{id}/tokens/{address}/info`
- Current: `PricingProperties`, `CoinGeckoHistoricalResolver`, `SpotPriceResolver`
