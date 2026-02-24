# ADR-005: Caffeine In-Process Cache over Redis for MVP

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

WalletRadar requires caching for:
1. **Historical prices** — CoinGecko responses per `(contractAddress, date)` — TTL 24h
2. **Spot prices** — CoinGecko `/simple/price` per asset — TTL 5min
3. **Portfolio snapshots** — aggregated time-series per wallet set — TTL 60min
4. **Cross-wallet AVCO** — on-request computation result per `(wallets, assetSymbol)` — TTL 5min
5. **Token metadata** — symbol, decimals per contract — TTL 24h

Two options considered:
- **Option A:** Redis (external in-memory store, network hop, shared across instances)
- **Option B:** Caffeine (in-process JVM cache, zero network overhead, single instance only)

## Decision

**Option B — Caffeine in-process cache for MVP.**

Redis is deferred to **Phase 2**, triggered by the need for horizontal scaling (>500 concurrent sessions or Caffeine eviction rate >30%).

## Rationale

| Concern | Redis | Caffeine |
|---------|-------|---------|
| Infrastructure cost | $15–100/month (managed) or VPS overhead | $0 — in JVM |
| Network latency | 1–5ms per cache hit | 0ms (in-process) |
| Operational complexity | Separate service to deploy, monitor, restart | None |
| Shared cache across instances | Yes — required for horizontal scaling | No — local to each instance |
| Cache warming on restart | Preserved (if Redis persists) | Lost on restart |
| MVP suitability | Overengineered | Perfect fit |

For a single-instance MVP on a $14–28/month VPS, adding Redis introduces:
- A second Docker container to manage
- Network I/O on every cache read
- A new failure point

The only scenario where Caffeine is insufficient is **horizontal scaling** — when multiple Spring Boot instances need a shared cache (e.g., instance A built a `crossWalletAvco` that instance B could reuse). This is a Phase 2 concern.

The migration path is deliberately minimal:
```java
// Phase 1: Caffeine
@Bean CacheManager caffeineCacheManager() { ... }

// Phase 2: Redis — swap CacheManager only, zero business logic changes
@Bean CacheManager redisCacheManager(RedisConnectionFactory factory) { ... }
```

All `@Cacheable` annotations use the same cache names — switching the `CacheManager` bean is the only code change required.

## Cache Configuration

| Cache | Key | TTL | Max Entries | Rationale |
|-------|-----|-----|-------------|-----------|
| `historicalPriceCache` | contractAddress+ISODate | 24h | 10,000 | Historical prices are immutable — 1 CoinGecko call per token×date ever |
| `spotPriceCache` | contractAddress | 5min | 500 | Snapshot job runs hourly; 5min TTL prevents duplicate calls within same job |
| `snapshotCache` | sorted(wallets)+range | 60min | 200 | Portfolio read endpoint; TTL matches snapshot freshness |
| `crossWalletAvcoCache` | sorted(wallets)+assetSymbol | 5min | 2,000 | On-request computation; short TTL ensures wallet additions are reflected quickly |
| `tokenMetaCache` | contractAddress+networkId | 24h | 5,000 | Decimals and symbol don't change |

## Consequences

- **Positive:** Zero infrastructure cost. Zero operational overhead. Zero network latency on cache hits.
- **Positive:** Clean upgrade path to Redis: swap one Spring bean, no business logic changes.
- **Negative:** Cache is lost on application restart. Acceptable — historical price cache warms quickly from CoinGecko within 1 backfill cycle; portfolio snapshots are in MongoDB.
- **Negative:** Cache is not shared across instances. Acceptable in Phase 1 (single instance). Becomes a problem only when horizontal scaling is needed.

## Review Trigger

Introduce Redis when:
- Deploying >1 Spring Boot instance (horizontal scaling)
- Caffeine eviction rate consistently >30% under production load
- Cache warm-up after restart causes noticeable CoinGecko rate limit pressure
