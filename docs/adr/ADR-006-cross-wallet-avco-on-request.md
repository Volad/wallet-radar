# ADR-006: crossWalletAvco Computed On-Request, Never Stored

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect  
**Supersedes:** SAD v1.0 design (stored as `walletAddress=null` in `portfolio_snapshots`)  
**Triggered by:** BA Architecture Review Report v1.0, BA-CRIT-02, BA-GAP-10

---

## Context

WalletRadar computes two AVCO values per asset:
- `perWalletAvco` — AVCO using events from one wallet only
- `crossWalletAvco` — AVCO treating all session wallets as a single entity

SAD v1.0 stored `crossWalletAvco` in `asset_positions` and stored aggregate snapshots with `walletAddress=null` in `portfolio_snapshots`.

BA Review identified two critical problems with this approach:

**Problem 1 — Session isolation failure:**  
User A has wallets `{0x1, 0x2}`. User B has wallets `{0x1, 0x3}`. Both have the same wallet `0x1`. If `crossWalletAvco` is stored per wallet, whose cross-wallet context does it represent? There is no backend concept of "session" — sessions exist only in the browser.

**Problem 2 — Stale value on wallet removal:**  
User removes a wallet from their session. The stored `crossWalletAvco` was computed including that wallet's events. It is now incorrect — but the backend has no trigger to invalidate it, because it never knew the wallet was "in session".

Two resolution options were considered:
- **Option A:** Compute on-request for exact `wallets[]` parameter in each API call
- **Option B:** Store per unique wallet-set hash (sorted SHA256 of addresses)

## Decision

**Option A — Compute on-request. Never store `crossWalletAvco`.**

## Rationale

| Concern | Store per wallet-set hash | On-request computation |
|---------|--------------------------|----------------------|
| Isolation correctness | Correct if wallet set is stable | Correct always — exact wallet set per call |
| Stale value on wallet add/remove | Must invalidate on every session change | Never stale — always reflects current `wallets[]` |
| Storage cost | O(wallets^2) combinations in worst case | Zero |
| Computation cost | Zero at read time | O(events per asset) — covered by Caffeine cache |
| Implementation complexity | High (session hashing, invalidation triggers) | Low |

The computation is fast: load `economic_events` for `(walletAddresses[], assetSymbol)` using the existing compound index `{walletAddress, assetSymbol, blockTimestamp}`, merge timeline in memory, run AVCO formula. Caffeine cache with key `sorted(wallets)+assetSymbol` and TTL 5min absorbs repeated reads within the same user session.

## What Changed from SAD v1.0

| Component | v1.0 | v2.0 |
|-----------|------|------|
| `asset_positions.crossWalletAvco` | Stored field | **Removed** |
| `portfolio_snapshots` with `walletAddress=null` | Stored | **Removed** — per-wallet only |
| `SnapshotAggregator` in `SnapshotCronJob` | Called every hour | **Removed from cron** |
| `SnapshotAggregationService` | — | On-request service, called from `SnapshotController` |
| `CrossWalletAvcoAggregatorService` | Cron component | On-request, Caffeine-cached |

## Consequences

- **Positive:** Always correct regardless of session composition. No invalidation logic needed.
- **Positive:** Removes `walletAddress=null` storage ambiguity — `portfolio_snapshots` is always per-wallet.
- **Positive:** `SnapshotCronJob` is simpler — builds per-wallet snapshots only, idempotent upsert.
- **Negative:** `GET /assets` now calls `CrossWalletAvcoAggregatorService` for each distinct asset. Mitigated by Caffeine cache (TTL 5min). First call for a new wallet set will hit MongoDB — subsequent calls within 5min are served from cache.
- **Negative:** `crossWalletAvco` is always global across all networks — cannot be filtered per-network. This is correct behaviour (see ADR note below) but may surprise users expecting a per-network breakdown.

## Note on Network Filtering

`crossWalletAvco` is intentionally **global across all networks**. Example: a user bought ETH on Ethereum Mainnet and sold it on Arbitrum. The AVCO must account for both events to be financially accurate. Filtering AVCO by network would produce a misleading cost basis.

The API returns `crossWalletAvcoNote: "Across all networks & wallets in session"` to make this explicit in the UI.
