# ADR-004: Snapshot-First Read Architecture

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

WalletRadar's portfolio view requires:
- Current USD value of each asset
- Unrealised P&L for each asset
- Portfolio total value over time (charts: 1D / 7D / 1M / 1Y / ALL)

Three approaches exist for serving this data:

- **Option A: Live computation** — on each GET, fetch current prices from CoinGecko and compute P&L on the fly
- **Option B: Snapshot-first** — pre-compute and store hourly snapshots; GET endpoints read from snapshots only
- **Option C: Hybrid** — snapshots for charts, live computation for current values

## Decision

**Option B — Snapshot-first.** GET endpoints make zero RPC calls and perform zero heavy computation on the request path.

## Rationale

### Option A problems

CoinGecko Free tier: 50 req/min. A portfolio with 10 assets would consume 10 req per user request. At 10 concurrent users = 100 req/min — immediately throttled.

Live computation on the request path couples API latency to:
- CoinGecko availability
- CoinGecko rate limits
- AVCO replay time (O(n) over event history)

This creates an unacceptable user experience and a fragile dependency chain on the critical path.

### Option B advantages

| Concern | Live computation | Snapshot-first |
|---------|-----------------|---------------|
| API response time | 500ms–5s (RPC + CoinGecko) | <150ms (MongoDB read) |
| CoinGecko coupling on read path | Yes — blocks every request | No — only during hourly cron |
| Scalability | Degrades with user count | Independent of user count |
| Data freshness | Real-time | Hourly (acceptable for DeFi portfolio tracking) |
| Complexity | Low write, high read | High write (cron), low read |

Hourly freshness is acceptable for a portfolio tracker. Users are not executing trades through WalletRadar — they are reviewing historical performance.

### crossWalletAvco: on-request exception

`crossWalletAvco` is the one exception to pure snapshot-first: it is computed on-request because:
- The wallet set is defined at query time (browser localStorage), not at snapshot time
- Different users share wallet addresses — there is no "session" at snapshot time
- The computation is fast (Caffeine cache TTL 5min, covered MongoDB index scan)
- Storing it would require a snapshot per unique wallet-set combination — combinatorially infeasible

## Consequences

- **Positive:** `GET /assets`, `GET /portfolio/snapshots`, `GET /charts/*` all serve from pre-computed data. Target <150ms.
- **Positive:** CoinGecko rate limit is consumed only during the hourly `SnapshotCronJob`, not on user requests.
- **Positive:** AVCO replay happens in background jobs (`BackfillJobRunner`, `RecalcJobService`) — not on the request path.
- **Negative:** Portfolio values are up to 1 hour stale. Mitigated by showing `lastUpdated` timestamp in UI.
- **Negative:** `SnapshotCronJob` must be fault-tolerant. Mitigated by idempotent upsert on `(walletAddress, networkId, snapshotTime)` UNIQUE index — a failed cron run is retried on the next schedule without data duplication.
- **Negative:** `crossWalletAvco` on-request requires a Caffeine cache layer to avoid per-request MongoDB scans. Implemented with TTL 5min, key `sorted(wallets)+assetSymbol`.

## Review Trigger

Reconsider if users require sub-minute price updates (e.g., for active trading context). In that case, add a WebSocket price feed layer on top of the snapshot base — do not remove snapshots.
