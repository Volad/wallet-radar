# Pricing (T-019, T-020)

Historical and spot price resolvers. Used by ingestion and snapshot.

---

## T-019 — Historical price resolver chain

- **Module(s):** pricing (resolver)
- **Description:** Implement `HistoricalPriceResolver` chain: (1) StablecoinResolver (USDC, USDT, DAI, GHO, USDe, FRAX → $1.00). (2) SwapDerivedResolver: from event/transaction tokenIn/tokenOut ratio. (3) CoinGeckoHistoricalResolver: `/coins/{id}/history` with date; token bucket 45 req/min; cache key (contractAddress, date) TTL 24h. Return price or UNKNOWN; on UNKNOWN caller flags event.
- **Doc refs:** 01-domain (Price Sources), 03-accounting (Price Resolution), 02-architecture (pricing/resolver)
- **DoD:** Chain and resolvers; unit tests per resolver; integration test with throttled/mocked CoinGecko.
- **Dependencies:** T-001, T-003 (cache)

**Acceptance criteria**
- Stablecoin → SwapDerived → CoinGecko chain; UNKNOWN when all fail; 45 req/min throttle; 24h cache; unit and integration tests pass.

---

## T-020 — SpotPriceResolver and price caches

- **Module(s):** pricing
- **Description:** Implement `SpotPriceResolver` for current price (e.g. CoinGecko `/simple/price`). Used by SnapshotBuilder. Wire Caffeine: spotPrice TTL 5min, historical TTL 24h per 02-architecture.
- **Doc refs:** 02-architecture (SpotPriceResolver, Cache Configuration)
- **DoD:** SpotPriceResolver; cache config; unit tests; integration test with mocked API.
- **Dependencies:** T-003, T-019

**Acceptance criteria**
- SpotPriceResolver implemented; caches wired; unit and integration tests pass.
